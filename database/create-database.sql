-- Run as a database administrator; no existing tables or data are removed.
CREATE DATABASE IF NOT EXISTS nutrimarket CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Configure an existing application account in config/api-keys.yml.
-- The application needs SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES and INDEX
-- on nutrimarket.* while spring.sql.init.mode=always initializes tables.
