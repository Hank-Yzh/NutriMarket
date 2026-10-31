-- Agent 5: local procurement. Local confirmation never creates a supermarket order.
CREATE TABLE IF NOT EXISTS shopping_basket (
    basket_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    state VARCHAR(40) NOT NULL,
    document LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_basket_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_basket_market FOREIGN KEY (result_id) REFERENCES market_result(result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS procurement_order (
    order_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    basket_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_procurement_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_order_basket FOREIGN KEY (basket_id) REFERENCES shopping_basket(basket_id),
    CONSTRAINT uk_order_request UNIQUE (member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
