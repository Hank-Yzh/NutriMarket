# Historical migration 001: email accounts to username primary keys

New databases use current `src/main/resources/schema.sql` without this migration. Existing email/UUID schemas use [001_username_primary_key.sql](001_username_primary_key.sql). It creates an intermediate schema: **continue with migration 002 before starting the current application**. Neither application startup nor CREATE TABLE IF NOT EXISTS applies this upgrade automatically.

## Steps

1. Stop the old application and all other writers, including outbox delivery. Back up and rehearse on an independent restored database. Allow space for both table sets. The account needs CREATE, read/write, ALTER, RENAME and stored-procedure privileges.
2. Connect to the MySQL 8.0+ database and explicitly assign every old account a unique username matching `^[a-z][a-z0-9_]{2,31}$`. Passwords stay unchanged. Do not guess ownership from email prefixes:

   ```sql
   SELECT id, email FROM member_account ORDER BY created_at;

   CREATE TABLE IF NOT EXISTS account_username_mapping (
       legacy_id VARCHAR(36) PRIMARY KEY,
       username VARCHAR(32) NOT NULL UNIQUE
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

   -- Replace the example UUID and username; provide one row per old account.
   INSERT INTO account_username_mapping (legacy_id, username)
   VALUES ('actual-legacy-user-uuid', 'alice');
   ```

3. From the project root, replace connection parameters and database name. `-p` prompts for the password. Do not use `--force`; stop on errors:

   ```bash
   mysql -h 127.0.0.1 -P 3306 -u your_database_user -p nutrimarket \
     < database/migrations/001_username_primary_key.sql
   ```

4. Verify the intermediate ownership, then run 002 without starting the current application. Members sign in with their assigned username and original password; old JWTs are invalid:

   ```sql
   SHOW CREATE TABLE member_account;
   SELECT username, enabled, created_at FROM member_account;
   SELECT record_id, member_id, JSON_UNQUOTE(JSON_EXTRACT(document, '$.memberId')) AS snapshot_owner
     FROM dietary_record;
   SELECT record_id, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.memberId')) AS payload_owner
     FROM dietary_outbox;
   ```

   After 001, memberId is the assigned username. After 002, it is the UUID; then verify sign-in and record queries. Account state, hashes, counts and delivery status must match their pre-migration values.

## Behaviour and recovery

The script verifies a unique mapping for every old account and consistent record/JSON ownership. Unowned demonstration records block migration until their real owner is established. Records already delivered to Agent 2 also block migration; coordinate downstream identity changes first. Check for accepted deliveries with lost acknowledgements even when local delivered is false. Extend the migration if other tables reference the account table through additional foreign keys.

After checks, the script copies data, verifies row counts and switches three tables using one RENAME TABLE. Original tables remain as member_account_email_backup, dietary_record_email_backup and dietary_outbox_email_backup; the mapping table is retained. The intermediate account table contains username as primary key, password_hash, enabled and created_at, without id or email.

Record and undelivered outbox JSON memberId values are updated together. fingerprint_member_id internally retains the identity used for the original request fingerprint, preserving retry idempotency even after photos are removed. It is neither an authentication identity nor an API response field.

MySQL DDL commits implicitly; ROLLBACK cannot undo the whole migration ([MySQL documentation](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)). Before the switch, errors leave original tables intact but may leave *_username_v2 intermediate tables. Inspect the error and data, verify no switch occurred, then clean intermediate tables if appropriate and retry. Never blindly delete backups. Mapping-validation failures occur before copying and can be corrected before rerunning.

Existing backup or intermediate tables cause a refusal to run, preventing accidental overwrites. For rollback after a successful switch, keep the application stopped and first check for new-version writes; never switch back blindly if new data exists. Schedule backup/mapping retention only after acceptance and an independent backup.

## Verified scope

The original migration was exercised on independent MySQL legacy tables: incomplete mappings were rejected; complete mappings preserved enabled state, hashes, original fingerprints and backups while updating record/outbox ownership. Business tests covered username identity, JWT, isolation and idempotent uploads after photo removal. The actual business database was not migrated during development.
