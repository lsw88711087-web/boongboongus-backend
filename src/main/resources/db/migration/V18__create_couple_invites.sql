CREATE TABLE couple_invites (
    id VARCHAR(80) PRIMARY KEY,
    owner_user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    code VARCHAR(40) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    active_slot SMALLINT CHECK (active_slot IS NULL OR active_slot = 1),
    UNIQUE (owner_user_id, active_slot)
);
