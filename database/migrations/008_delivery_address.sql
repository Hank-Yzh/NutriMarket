-- Adds the member's saved delivery address. Safe to run repeatedly.
-- Agent 5: the member's saved delivery address, used only for home-delivery store orders.
CREATE TABLE IF NOT EXISTS member_delivery_address (
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    document LONGTEXT NOT NULL,
    CONSTRAINT fk_address_member FOREIGN KEY (member_id) REFERENCES member_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
