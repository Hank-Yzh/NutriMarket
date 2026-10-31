-- Upgrade the username-primary-key schema (after 001, if originally using email).
-- Stop all application instances and writers before running with the MySQL CLI.
-- Original tables are retained as *_username_backup. Do not run with --force.
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_member_id_primary_key$$
CREATE PROCEDURE migrate_member_id_primary_key()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (
        'member_account_username_backup','dietary_record_username_backup','dietary_outbox_username_backup',
        'member_account_id_v3','dietary_record_id_v3','dietary_outbox_id_v3')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backup/staging tables already exist. Inspect them before retrying; nothing has been overwritten.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='member_account' AND column_name='id') OR
       NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='member_account' AND column_name='username') OR
       NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='dietary_record' AND column_name='fingerprint_member_id') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected username-primary-key schema. Fresh databases must use schema.sql; email schema must first use 001.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE referenced_table_schema=DATABASE()
            AND (referenced_table_name='member_account'
              OR (referenced_table_name='dietary_record' AND table_name<>'dietary_outbox')
              OR referenced_table_name='dietary_outbox')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Additional foreign keys reference these tables. Extend this migration before proceeding.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record d LEFT JOIN member_account u ON d.member_id=u.username WHERE u.username IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Some meals have no matching username. Resolve ownership before migrating.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_outbox WHERE delivered=TRUE) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Some meals were delivered downstream. Coordinate downstream identity migration first.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record WHERE NOT JSON_VALID(document)) OR
       EXISTS (SELECT 1 FROM dietary_outbox WHERE NOT JSON_VALID(payload)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid JSON snapshots found. No business tables have been changed.';
    END IF;
    IF EXISTS (SELECT 1 FROM dietary_record WHERE NOT (JSON_UNQUOTE(JSON_EXTRACT(document,'$.memberId')) <=> member_id)) OR
       EXISTS (SELECT 1 FROM dietary_outbox o JOIN dietary_record d ON o.record_id=d.record_id
            WHERE NOT (JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.memberId')) <=> d.member_id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Snapshot ownership disagrees with record ownership. Resolve before migrating.';
    END IF;

    CREATE TABLE member_account_id_v3 (
        id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        username VARCHAR(32) NOT NULL UNIQUE,
        password_hash VARCHAR(100) NOT NULL,
        enabled BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMP(3) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
    INSERT INTO member_account_id_v3 (id,username,password_hash,enabled,created_at)
        SELECT UUID(),username,password_hash,enabled,created_at FROM member_account;

    CREATE TABLE dietary_record_id_v3 LIKE dietary_record;
    ALTER TABLE dietary_record_id_v3
        MODIFY member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        ADD CONSTRAINT fk_record_member_id_v3 FOREIGN KEY (member_id) REFERENCES member_account_id_v3(id);
    INSERT INTO dietary_record_id_v3
        (record_id,member_id,fingerprint,fingerprint_member_id,status,created_at,expires_at,document,photo,media_type)
        SELECT d.record_id,u.id,d.fingerprint,d.fingerprint_member_id,d.status,d.created_at,d.expires_at,
               JSON_SET(d.document,'$.memberId',u.id),d.photo,d.media_type
        FROM dietary_record d JOIN member_account_id_v3 u ON d.member_id=u.username;

    -- CREATE TABLE LIKE omits foreign keys; attach the queue to the new parent table.
    CREATE TABLE dietary_outbox_id_v3 LIKE dietary_outbox;
    ALTER TABLE dietary_outbox_id_v3 ADD CONSTRAINT fk_outbox_member_id_v3
        FOREIGN KEY (record_id) REFERENCES dietary_record_id_v3(record_id);
    INSERT INTO dietary_outbox_id_v3 (record_id,payload,delivered,attempts,last_error,updated_at)
        SELECT o.record_id,JSON_SET(o.payload,'$.memberId',u.id),o.delivered,o.attempts,o.last_error,o.updated_at
        FROM dietary_outbox o JOIN dietary_record d ON o.record_id=d.record_id
        JOIN member_account_id_v3 u ON d.member_id=u.username;

    IF (SELECT COUNT(*) FROM member_account)<>(SELECT COUNT(*) FROM member_account_id_v3) OR
       (SELECT COUNT(*) FROM dietary_record)<>(SELECT COUNT(*) FROM dietary_record_id_v3) OR
       (SELECT COUNT(*) FROM dietary_outbox)<>(SELECT COUNT(*) FROM dietary_outbox_id_v3) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Copy counts disagree. Original tables remain active; inspect staging tables.';
    END IF;
    RENAME TABLE member_account TO member_account_username_backup, member_account_id_v3 TO member_account,
        dietary_record TO dietary_record_username_backup, dietary_record_id_v3 TO dietary_record,
        dietary_outbox TO dietary_outbox_username_backup, dietary_outbox_id_v3 TO dietary_outbox;
END$$
DELIMITER ;
CALL migrate_member_id_primary_key();
DROP PROCEDURE migrate_member_id_primary_key;
