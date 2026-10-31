# Database migrations

The current schema uses UUID `member_account.id` as primary key and a unique username for login. `dietary_record.member_id` references the account ID; `dietary_outbox.record_id` references the meal record.

| Existing database | Action |
|---|---|
| New database | Create the database, then apply current `src/main/resources/schema.sql`; do not run historical migrations |
| Username primary key without id | Apply [002_member_id_primary_key.sql](002_member_id_primary_key.sql) |
| Original email/UUID account version | Assign usernames through migration 001, then run 002 |

## Upgrade username primary keys to member IDs

1. Stop the application, delivery workers and other writers. Back up the database and rehearse on an independent restored copy. Allow space for original and replacement tables. The database account needs CREATE, ALTER, RENAME, read/write and stored-procedure privileges.
2. Run from the project root, replacing connection parameters. Do not add `--force`:

   ```bash
   mysql -h 127.0.0.1 -P 3306 -u your_database_user -p nutrimarket \
     < database/migrations/002_member_id_primary_key.sql
   ```

3. Check associations, start the new application and sign in again. Old username-identity JWTs are invalid:

   ```sql
   SHOW CREATE TABLE member_account;
   SHOW CREATE TABLE dietary_record;
   SELECT id, username, enabled FROM member_account;
   SELECT d.record_id, d.member_id, u.username
     FROM dietary_record d JOIN member_account u ON d.member_id = u.id;
   SELECT o.record_id, JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.memberId')) AS payload_member_id
     FROM dietary_outbox o;
   ```

The script generates one UUID per existing account and updates record ownership columns and memberId in both business JSON snapshots. It preserves usernames, hashes, enabled state, creation time, photos and delivery state. fingerprint and fingerprint_member_id remain unchanged so retries of historical uploads remain idempotent. The latter is internal fingerprint metadata, not an authorisation identity.

Foreign keys use RESTRICT, without cascading deletes. Accounts with meal records and meals with outbox rows cannot be deleted directly. The outbox resolves the member through its meal, avoiding a second potentially inconsistent member column.

After copying and validating data, one RENAME TABLE switches the three tables. Original tables remain as member_account_username_backup, dietary_record_username_backup and dietary_outbox_username_backup. The script does not erase business data or overwrite prior backup/intermediate tables. MySQL DDL commits implicitly, so ROLLBACK cannot undo the whole migration. If failure occurs before switching, inspect *_id_v3 intermediate tables and verify the original tables remain active before cleanup. Do not blindly remove backups.

Unmatched accounts, inconsistent JSON ownership, additional foreign keys and already-delivered downstream records block migration. Coordinate downstream identity updates first, including cases where acceptance occurred but the acknowledgement was lost. Do not bypass checks by changing delivered flags.

schema.sql does not upgrade old tables automatically. Migrate before restarting. Its default admin / elec5620 initialisation resets the password and enables the account each time it runs, but preserves the generated ID. Migration 002 itself does not reset passwords.

## Verification and historical migration

The original 50 H2 tests covered foreign keys, ownership, old-JWT rejection, separate username/ID identity and preservation of the admin ID during repeated initialisation. Migration 002 has not been verified against the actual user MySQL database; rehearse on an independent database first.

For the email version, follow [migration 001 instructions](001_username_primary_key.md), then run 002 before starting the current application.

## Agent 2 tables: migration 003

Databases already using UUID member IDs can run [003_health_agent.sql](003_health_agent.sql), or restart with spring.sql.init.mode=always so schema.sql creates the new tables. This repeatable script adds versioned health profiles, intake receipts and weekly assessments. Do not rerun 001/002. An older username-primary-key database must complete 002 first.

## Agent 3 tables: migration 004

After the UUID member schema and Agent 2 tables exist, apply [004_nutrition_planning.sql](004_nutrition_planning.sql), or restart with `spring.sql.init.mode=always`. It creates `nutrition_plan` and `planning_outbox` without rewriting existing data. The script is repeatable. New databases use the current `schema.sql` directly and do not need historical migrations.

## Agent 4 tables: migration 005

After the UUID member, Agent 2 and Agent 3 schemas exist, apply [005_market_intelligence.sql](005_market_intelligence.sql), or restart with startup schema initialisation enabled. It adds local product evidence/history, market results and a procurement outbox. No example products are inserted. Fresh databases use the current schema directly.

## Agent 5 local procurement

After migration 005, apply `006_procurement_agent.sql` to add basket and local-order tables. Startup schema initialization creates these same missing tables automatically when enabled. No existing account/product/plan rows are rewritten.

## Member pantry

After migration 006, apply `007_member_pantry.sql` to add `member_pantry`, which stores only a member's manual pantry corrections; pantry stock is recomputed from orders. Startup schema initialisation creates the same table when enabled. No existing rows are rewritten.

## Delivery address

After migration 007, apply `008_delivery_address.sql` to add `member_delivery_address`, the member's saved address for home-delivery orders. It is kept apart from the health profile, so it is never sent to Qwen or shown to experts. Startup schema initialisation creates the same table when enabled. No existing rows are rewritten.

## Disliked foods

After migration 008, apply `009_food_dislikes.sql` to add `member_food_dislike`, the foods a member marked as disliked (from the health profile page or a basket's **Don't like this food**). Agent 3 leaves them out of new plans and preference replans. They are kept apart from the versioned health profile, so adding one never invalidates the current assessment. Startup schema initialisation creates the same table when enabled. No existing rows are rewritten.
