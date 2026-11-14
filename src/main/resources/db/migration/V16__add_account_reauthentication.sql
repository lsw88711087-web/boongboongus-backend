ALTER TABLE auth_sessions ADD COLUMN user_id VARCHAR(80) REFERENCES auth_users (id);

CREATE TABLE auth_reauth_proofs (
    token_hash CHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    purpose VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);
