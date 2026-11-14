CREATE TABLE radio_episodes (
    job_id VARCHAR(80) PRIMARY KEY REFERENCES ai_jobs(id) ON DELETE CASCADE,
    owner_user_id VARCHAR(80) NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
    drive_id VARCHAR(80) REFERENCES drives(id),
    mode VARCHAR(16) NOT NULL,
    story VARCHAR(600),
    broadcast_text TEXT,
    audio BYTEA
);
