CREATE TABLE ai_jobs (
    id VARCHAR(80) PRIMARY KEY,
    owner_user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('PLAYLIST_GENERATION', 'RADIO_GENERATION', 'GAME_GENERATION')),
    status VARCHAR(16) NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'
    )),
    resource_id VARCHAR(80),
    error_snapshot TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ai_jobs_status_expiry ON ai_jobs (status, expires_at);
