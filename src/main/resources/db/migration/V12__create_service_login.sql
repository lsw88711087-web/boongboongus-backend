ALTER TABLE auth_sessions ADD COLUMN consumed_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE auth_users (
    id VARCHAR(80) PRIMARY KEY,
    kakao_id BIGINT NOT NULL UNIQUE,
    nickname VARCHAR(20) NOT NULL,
    birth_date DATE,
    profile_image_url TEXT,
    gender VARCHAR(16),
    onboarding_completed BOOLEAN NOT NULL
);

CREATE TABLE auth_service_sessions (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);
