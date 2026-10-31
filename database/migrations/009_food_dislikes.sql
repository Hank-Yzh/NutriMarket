-- Adds the member's disliked foods. Safe to run repeatedly.
-- Agent 3: foods the member does not want in meal plans; kept apart from the versioned health profile.
CREATE TABLE IF NOT EXISTS member_food_dislike (
    member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ingredient_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id, ingredient_id),
    CONSTRAINT fk_dislike_member FOREIGN KEY (member_id) REFERENCES member_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
