CREATE TABLE playlist_requests (
    job_id VARCHAR(80) PRIMARY KEY REFERENCES ai_jobs (id) ON DELETE CASCADE,
    request_snapshot TEXT NOT NULL
);

CREATE TABLE playlists (
    id VARCHAR(80) PRIMARY KEY,
    owner_user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    drive_id VARCHAR(80) REFERENCES drives (id),
    result_snapshot TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
