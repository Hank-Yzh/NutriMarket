-- Run after 002 when upgrading an existing database. Additive and safe to rerun.

-- Agent 2: immutable profile versions, idempotent intake receipt and weekly assessment snapshots.
CREATE TABLE IF NOT EXISTS member_health_profile (
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    profile_version BIGINT NOT NULL,
    document LONGTEXT NOT NULL,
    PRIMARY KEY (member_id, profile_version),
    CONSTRAINT fk_health_profile_member FOREIGN KEY (member_id) REFERENCES member_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS health_intake (
    record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    profile_version BIGINT NOT NULL,
    meal_time TIMESTAMP(3) NOT NULL,
    document LONGTEXT NOT NULL,
    CONSTRAINT fk_health_intake_record FOREIGN KEY (record_id) REFERENCES dietary_record(record_id),
    CONSTRAINT fk_health_intake_member FOREIGN KEY (member_id) REFERENCES member_account(id),
    INDEX idx_health_intake_period (member_id, meal_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS weekly_health_assessment (
    assessment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    profile_version BIGINT NOT NULL,
    period_start DATE NOT NULL,
    clearance VARCHAR(40) NOT NULL,
    document LONGTEXT NOT NULL,
    CONSTRAINT fk_assessment_profile FOREIGN KEY (member_id, profile_version) REFERENCES member_health_profile(member_id, profile_version),
    UNIQUE KEY uk_assessment_period (member_id, profile_version, period_start),
    INDEX idx_health_review (clearance, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
