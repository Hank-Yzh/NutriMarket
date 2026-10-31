-- Run with the MySQL CLI against the OLD email/UUID schema, while the application is stopped.
-- This script preserves the old tables as *_email_backup and switches all three tables atomically.
-- Fill the mapping below BEFORE the CALL. Do not invent usernames for existing users.
CREATE TABLE IF NOT EXISTS account_username_mapping (
    legacy_id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Inspect existing accounts: SELECT id, email FROM member_account;
-- INSERT INTO account_username_mapping (legacy_id, username) VALUES ('existing-user-uuid', 'alice');

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_username_primary_key$$
CREATE PROCEDURE migrate_username_primary_key()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (
        'member_account_email_backup', 'dietary_record_email_backup', 'dietary_outbox_email_backup',
        'member_account_username_v2', 'dietary_record_username_v2', 'dietary_outbox_username_v2')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backup/staging tables already exist. Inspect them before retrying; nothing has been overwritten.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='member_account' AND column_name='email') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected old email/UUID schema. Do not run this on the new username schema.';
    END IF;
    IF EXISTS (SELECT 1 FROM member_account u LEFT JOIN account_username_mapping m ON m.legacy_id=u.id
            WHERE m.legacy_id IS NULL) OR EXISTS (
            SELECT 1 FROM account_username_mapping m LEFT JOIN member_account u ON m.legacy_id=u.id WHERE u.id IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Provide exactly one username mapping for every existing account before migrating.';
    END IF;
    IF EXISTS (SELECT 1 FROM account_username_mapping WHERE NOT REGEXP_LIKE(username,'^[a-z][a-z0-9_]{2,31}$','c')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Usernames must be 3-32 lowercase ASCII letters/digits/underscore, starting with a letter.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE referenced_table_schema=DATABASE()
            AND referenced_table_name='member_account') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Additional foreign keys reference accounts. Extend this migration before proceeding.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record d LEFT JOIN account_username_mapping m ON d.member_id=m.legacy_id
            WHERE m.legacy_id IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Some meals have no mapped account. Resolve ownership explicitly before migrating.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_outbox WHERE delivered=TRUE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Some meals were delivered downstream. Coordinate downstream identity migration first.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record WHERE NOT JSON_VALID(document)) OR
       EXISTS (SELECT 1 FROM dietary_outbox WHERE NOT JSON_VALID(payload)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid JSON snapshots found. No tables have been changed.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record WHERE NOT (JSON_UNQUOTE(JSON_EXTRACT(document,'$.memberId')) <=> member_id)) OR
       EXISTS (SELECT 1 FROM dietary_outbox o JOIN dietary_record d ON o.record_id=d.record_id
            WHERE NOT (JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.memberId')) <=> d.member_id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Snapshot ownership disagrees with record ownership. Resolve before migrating.';
    END IF;

    CREATE TABLE member_account_username_v2 (
        username VARCHAR(32) PRIMARY KEY,
        password_hash VARCHAR(100) NOT NULL,
        enabled BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMP(3) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
    INSERT INTO member_account_username_v2 (username,password_hash,enabled,created_at)
        SELECT m.username,u.password_hash,u.enabled,u.created_at
        FROM member_account u JOIN account_username_mapping m ON m.legacy_id=u.id;

    CREATE TABLE dietary_record_username_v2 LIKE dietary_record;
    ALTER TABLE dietary_record_username_v2 ADD COLUMN fingerprint_member_id VARCHAR(100) NOT NULL AFTER fingerprint;
    INSERT INTO dietary_record_username_v2
        (record_id,member_id,fingerprint,fingerprint_member_id,status,created_at,expires_at,document,photo,media_type)
        SELECT d.record_id,m.username,d.fingerprint,d.member_id,d.status,d.created_at,d.expires_at,
               JSON_SET(d.document,'$.memberId',m.username),d.photo,d.media_type
        FROM dietary_record d JOIN account_username_mapping m ON m.legacy_id=d.member_id;

    -- CREATE TABLE LIKE does not copy foreign keys. Attach a new constraint to the staging parent.
    CREATE TABLE dietary_outbox_username_v2 LIKE dietary_outbox;
    ALTER TABLE dietary_outbox_username_v2 ADD CONSTRAINT fk_outbox_username_v2
        FOREIGN KEY (record_id) REFERENCES dietary_record_username_v2(record_id);
    INSERT INTO dietary_outbox_username_v2 (record_id,payload,delivered,attempts,last_error,updated_at)
        SELECT o.record_id,JSON_SET(o.payload,'$.memberId',m.username),o.delivered,o.attempts,o.last_error,o.updated_at
        FROM dietary_outbox o JOIN dietary_record d ON o.record_id=d.record_id
        JOIN account_username_mapping m ON m.legacy_id=d.member_id;

    IF (SELECT COUNT(*) FROM member_account)<>(SELECT COUNT(*) FROM member_account_username_v2) OR
       (SELECT COUNT(*) FROM dietary_record)<>(SELECT COUNT(*) FROM dietary_record_username_v2) OR
       (SELECT COUNT(*) FROM dietary_outbox)<>(SELECT COUNT(*) FROM dietary_outbox_username_v2) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Copy counts disagree. Original tables remain active; inspect staging tables.';
    END IF;
    RENAME TABLE member_account TO member_account_email_backup, member_account_username_v2 TO member_account,
        dietary_record TO dietary_record_email_backup, dietary_record_username_v2 TO dietary_record,
        dietary_outbox TO dietary_outbox_email_backup, dietary_outbox_username_v2 TO dietary_outbox;
END$$
DELIMITER ;
CALL migrate_username_primary_key();
DROP PROCEDURE migrate_username_primary_key;
