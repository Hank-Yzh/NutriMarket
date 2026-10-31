-- Agent 3 additions for the current UUID member schema. Safe to run repeatedly.
CREATE TABLE IF NOT EXISTS nutrition_plan (
    plan_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    profile_version BIGINT NOT NULL,
    week_start DATE NOT NULL,
    state VARCHAR(40) NOT NULL,
    document LONGTEXT NOT NULL,
    CONSTRAINT fk_plan_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    CONSTRAINT fk_plan_assessment FOREIGN KEY (assessment_id) REFERENCES weekly_health_assessment(assessment_id),
    CONSTRAINT uk_plan_week UNIQUE (member_id, assessment_id, week_start),
    INDEX idx_plan_review (state, week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS planning_outbox (
    plan_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_version INTEGER NOT NULL,
    payload LONGTEXT NOT NULL,
    state VARCHAR(40) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(80),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (plan_id, plan_version),
    CONSTRAINT fk_planning_outbox FOREIGN KEY (plan_id) REFERENCES nutrition_plan(plan_id),
    INDEX idx_planning_delivery (state, attempts, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
