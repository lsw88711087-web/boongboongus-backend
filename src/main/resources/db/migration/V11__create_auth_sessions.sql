CREATE TABLE auth_sessions (
    id VARCHAR(80) PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    redirect_uri TEXT NOT NULL,
    code_challenge VARCHAR(43) NOT NULL,
    state VARCHAR(43) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
