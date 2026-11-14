ALTER TABLE device_push_tokens ADD COLUMN platform VARCHAR(7)
    CHECK (platform IN ('IOS', 'ANDROID', 'WEB'));
ALTER TABLE device_push_tokens ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
