CREATE TABLE notifications (
    id VARCHAR(80) PRIMARY KEY,
    recipient_user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id),
    sender_user_id VARCHAR(80) REFERENCES auth_users (id) ON DELETE SET NULL,
    type VARCHAR(32) NOT NULL CHECK (type IN (
        'PLACE_SHARED', 'REFLECTION_SUBMITTED', 'COUPLE_CONNECTED', 'COUPLE_DISCONNECTED'
    )),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('PLACE', 'CARD', 'COUPLE')),
    target_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL CHECK (expires_at > created_at),
    read_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX notifications_recipient_created ON notifications (recipient_user_id, created_at DESC, id DESC);
CREATE INDEX notifications_expiry ON notifications (expires_at);
