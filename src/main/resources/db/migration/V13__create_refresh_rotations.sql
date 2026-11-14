CREATE TABLE auth_refresh_rotations (
    refresh_hash CHAR(64) PRIMARY KEY,
    session_id VARCHAR(80) NOT NULL REFERENCES auth_service_sessions (id),
    request_key VARCHAR(128) NOT NULL,
    retry_until TIMESTAMP WITH TIME ZONE NOT NULL,
    sealed_response TEXT,
    UNIQUE (session_id, request_key)
);

CREATE INDEX auth_refresh_rotations_retry_expiry ON auth_refresh_rotations (retry_until);
