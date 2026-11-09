CREATE TABLE players (
    id         UUID         PRIMARY KEY,
    username   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_players_username UNIQUE (username),
    -- Same rule as the API validation, enforced again by the database.
    CONSTRAINT ck_players_username_format CHECK (username ~ '^[A-Za-z0-9_]{3,20}$')
);

-- Every submission is kept, not just the best one, so the leaderboard can always be rebuilt from here.
CREATE TABLE score_submissions (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id    UUID         NOT NULL REFERENCES players (id),
    score        BIGINT       NOT NULL,
    submitted_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_score_submissions_score_non_negative CHECK (score >= 0)
);

-- Supports "best submission per player" (highest score, earliest time), which the Redis rebuild reads.
CREATE INDEX idx_score_submissions_best
    ON score_submissions (player_id, score DESC, submitted_at ASC);
