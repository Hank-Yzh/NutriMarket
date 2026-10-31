-- Adds the member pantry (manual stock corrections). Safe to run repeatedly.
-- Member pantry: manual corrections only; stock itself is recomputed from orders.
CREATE TABLE IF NOT EXISTS member_pantry (
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    document LONGTEXT NOT NULL,
    CONSTRAINT fk_pantry_member FOREIGN KEY (member_id) REFERENCES member_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
