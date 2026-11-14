CREATE TABLE device_push_tokens (
    device_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    push_token TEXT NOT NULL
);
