-- Agent 4. Apply after the UUID member schema and Agent 3 tables. Repeatable additions only.
CREATE TABLE IF NOT EXISTS market_product (
    product_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    version INTEGER NOT NULL,
    ingredient_id VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL,
    document LONGTEXT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_market_product_ingredient (ingredient_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS market_product_history (
    product_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INTEGER NOT NULL,
    document LONGTEXT NOT NULL,
    PRIMARY KEY (product_id, version),
    CONSTRAINT fk_product_history FOREIGN KEY (product_id) REFERENCES market_product(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS market_result (
    result_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_version INTEGER NOT NULL,
    document LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_market_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_market_plan FOREIGN KEY (plan_id, plan_version) REFERENCES planning_outbox(plan_id, plan_version),
    CONSTRAINT uk_market_plan UNIQUE (plan_id, plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS market_outbox (
    result_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INTEGER NOT NULL,
    payload LONGTEXT NOT NULL,
    state VARCHAR(40) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (result_id, revision),
    CONSTRAINT fk_market_outbox FOREIGN KEY (result_id) REFERENCES market_result(result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
