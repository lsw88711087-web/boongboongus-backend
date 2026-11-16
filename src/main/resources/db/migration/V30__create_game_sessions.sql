CREATE TABLE game_sessions (
    id VARCHAR(80) PRIMARY KEY,
    job_id VARCHAR(80) NOT NULL UNIQUE REFERENCES ai_jobs (id),
    drive_id VARCHAR(80) NOT NULL REFERENCES drives (id),
    game_type VARCHAR(16) NOT NULL CHECK (game_type IN ('BALANCE', 'NONSENSE', 'MEMORY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABANDONED')),
    question_number INTEGER NOT NULL CHECK (question_number BETWEEN 1 AND 5),
    version INTEGER NOT NULL CHECK (version >= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX game_sessions_drive_status ON game_sessions (drive_id, status);

CREATE TABLE game_generation_requests (
    job_id VARCHAR(80) PRIMARY KEY REFERENCES ai_jobs (id) ON DELETE CASCADE,
    drive_id VARCHAR(80) NOT NULL REFERENCES drives (id),
    game_type VARCHAR(16) NOT NULL CHECK (game_type IN ('BALANCE', 'NONSENSE', 'MEMORY'))
);

CREATE TABLE game_questions (
    id VARCHAR(80) PRIMARY KEY,
    session_id VARCHAR(80) NOT NULL REFERENCES game_sessions (id),
    question_number INTEGER NOT NULL CHECK (question_number BETWEEN 1 AND 5),
    prompt VARCHAR(300) NOT NULL,
    option_a VARCHAR(100),
    option_b VARCHAR(100),
    hint VARCHAR(100),
    answer VARCHAR(100),
    hint_revealed BOOLEAN NOT NULL DEFAULT FALSE,
    answer_revealed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (session_id, question_number)
);

CREATE TABLE game_responses (
    question_id VARCHAR(80) NOT NULL REFERENCES game_questions (id),
    user_id VARCHAR(80) NOT NULL,
    choice VARCHAR(1),
    selected_role VARCHAR(16),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (question_id, user_id),
    CHECK ((choice IS NOT NULL AND choice IN ('A', 'B') AND selected_role IS NULL)
        OR (choice IS NULL AND selected_role IS NOT NULL AND selected_role IN ('DRIVER', 'PASSENGER')))
);
