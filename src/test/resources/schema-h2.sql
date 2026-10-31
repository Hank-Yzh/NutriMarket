CREATE TABLE IF NOT EXISTS member_account (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL
);

-- Default account: admin / elec5620. Reset its password and enable it on every initialization.
-- Store only the BCrypt hash; preserve id and created_at when the account already exists.
INSERT INTO member_account (id, username, password_hash, enabled, created_at)
VALUES (RANDOM_UUID(), 'admin', '$2a$12$iwQ5ffoGzIYjs5sf76hx3eIZyKIyPHuEmEkJU0eOsKWx2GK3Q95PG', TRUE, CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE password_hash = '$2a$12$iwQ5ffoGzIYjs5sf76hx3eIZyKIyPHuEmEkJU0eOsKWx2GK3Q95PG', enabled = TRUE;


-- Fast offline tests only. Production uses schema.sql against MySQL.
CREATE TABLE IF NOT EXISTS dietary_record (
    record_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    fingerprint_member_id VARCHAR(100) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3),
    document CLOB NOT NULL,
    photo BLOB,
    media_type VARCHAR(30) NOT NULL,
    CONSTRAINT fk_record_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
CREATE TABLE IF NOT EXISTS dietary_outbox (
    record_id VARCHAR(36) PRIMARY KEY,
    payload CLOB NOT NULL,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(80),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_outbox_record FOREIGN KEY (record_id) REFERENCES dietary_record(record_id)
);

-- Agent 2: immutable profile versions, idempotent intake receipt and weekly assessment snapshots.
CREATE TABLE IF NOT EXISTS member_health_profile (
    member_id VARCHAR(36) NOT NULL,
    profile_version BIGINT NOT NULL,
    document CLOB NOT NULL,
    PRIMARY KEY (member_id, profile_version),
    CONSTRAINT fk_health_profile_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
CREATE TABLE IF NOT EXISTS health_intake (
    record_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    profile_version BIGINT NOT NULL,
    meal_time TIMESTAMP(3) NOT NULL,
    document CLOB NOT NULL,
    CONSTRAINT fk_health_intake_record FOREIGN KEY (record_id) REFERENCES dietary_record(record_id),
    CONSTRAINT fk_health_intake_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
CREATE TABLE IF NOT EXISTS weekly_health_assessment (
    assessment_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    profile_version BIGINT NOT NULL,
    period_start DATE NOT NULL,
    clearance VARCHAR(40) NOT NULL,
    document CLOB NOT NULL,
    CONSTRAINT fk_assessment_profile FOREIGN KEY (member_id, profile_version) REFERENCES member_health_profile(member_id, profile_version),
    CONSTRAINT uk_assessment_period UNIQUE (member_id, profile_version, period_start)
);

-- Agent 3 additions for the current UUID member schema. Safe to run repeatedly.
CREATE TABLE IF NOT EXISTS nutrition_plan (
    plan_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    profile_version BIGINT NOT NULL,
    week_start DATE NOT NULL,
    state VARCHAR(40) NOT NULL,
    document CLOB NOT NULL,
    CONSTRAINT fk_plan_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_plan_assessment FOREIGN KEY (assessment_id) REFERENCES weekly_health_assessment(assessment_id),
    CONSTRAINT uk_plan_week UNIQUE (member_id, assessment_id, week_start)
);
CREATE TABLE IF NOT EXISTS planning_outbox (
    plan_id VARCHAR(36) NOT NULL,
    plan_version INTEGER NOT NULL,
    payload CLOB NOT NULL,
    state VARCHAR(40) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(80),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (plan_id, plan_version),
    CONSTRAINT fk_planning_outbox FOREIGN KEY (plan_id) REFERENCES nutrition_plan(plan_id)
);

-- Agent 4. Apply after the UUID member schema and Agent 3 tables. Repeatable additions only.
CREATE TABLE IF NOT EXISTS market_product (
    product_id VARCHAR(36) PRIMARY KEY,
    version INTEGER NOT NULL,
    ingredient_id VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL,
    document CLOB NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS market_product_history (
    product_id VARCHAR(36) NOT NULL,
    version INTEGER NOT NULL,
    document CLOB NOT NULL,
    PRIMARY KEY (product_id, version),
    CONSTRAINT fk_product_history FOREIGN KEY (product_id) REFERENCES market_product(product_id)
);
CREATE TABLE IF NOT EXISTS market_result (
    result_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    plan_version INTEGER NOT NULL,
    document CLOB NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_market_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_market_plan FOREIGN KEY (plan_id, plan_version) REFERENCES planning_outbox(plan_id, plan_version),
    CONSTRAINT uk_market_plan UNIQUE (plan_id, plan_version)
);
CREATE TABLE IF NOT EXISTS market_outbox (
    result_id VARCHAR(36) NOT NULL,
    revision INTEGER NOT NULL,
    payload CLOB NOT NULL,
    state VARCHAR(40) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (result_id, revision),
    CONSTRAINT fk_market_outbox FOREIGN KEY (result_id) REFERENCES market_result(result_id)
);

-- Agent 5: local procurement. Local confirmation never creates a supermarket order.
CREATE TABLE IF NOT EXISTS shopping_basket (
    basket_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    result_id VARCHAR(36) NOT NULL UNIQUE,
    state VARCHAR(40) NOT NULL,
    document CLOB NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_basket_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_basket_market FOREIGN KEY (result_id) REFERENCES market_result(result_id)
);
CREATE TABLE IF NOT EXISTS procurement_order (
    order_id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    basket_id VARCHAR(36) NOT NULL UNIQUE,
    request_id VARCHAR(36) NOT NULL,
    document CLOB NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_procurement_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_order_basket FOREIGN KEY (basket_id) REFERENCES shopping_basket(basket_id),
    CONSTRAINT uk_order_request UNIQUE (member_id, request_id)
);

CREATE TABLE IF NOT EXISTS member_pantry (
    member_id VARCHAR(36) PRIMARY KEY,
    document CLOB NOT NULL,
    CONSTRAINT fk_pantry_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
CREATE TABLE IF NOT EXISTS member_delivery_address (
    member_id VARCHAR(36) PRIMARY KEY,
    document CLOB NOT NULL,
    CONSTRAINT fk_address_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
CREATE TABLE IF NOT EXISTS member_food_dislike (
    member_id VARCHAR(36) NOT NULL,
    ingredient_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id, ingredient_id),
    CONSTRAINT fk_dislike_member FOREIGN KEY (member_id) REFERENCES member_account(id)
);
