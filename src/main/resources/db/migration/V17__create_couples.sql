CREATE TABLE couples (
    id VARCHAR(80) PRIMARY KEY,
    first_member_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    second_member_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    start_date DATE NOT NULL,
    mascot_name VARCHAR(20) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    disconnected_at TIMESTAMP WITH TIME ZONE,
    CHECK (first_member_id <> second_member_id)
);

CREATE INDEX couples_first_member ON couples (first_member_id);
CREATE INDEX couples_second_member ON couples (second_member_id);
