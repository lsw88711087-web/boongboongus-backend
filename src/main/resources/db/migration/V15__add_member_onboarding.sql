ALTER TABLE auth_users ADD COLUMN couple_start_date DATE;
ALTER TABLE auth_users ADD COLUMN mascot_name VARCHAR(20);

CREATE TABLE auth_user_consents (
    user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    document_type VARCHAR(20) NOT NULL,
    document_version TEXT NOT NULL,
    agreed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, document_type, document_version)
);
