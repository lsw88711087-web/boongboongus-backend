CREATE TABLE playlist_bookmarks (
    user_id VARCHAR(80) NOT NULL REFERENCES auth_users (id) ON DELETE CASCADE,
    playlist_id VARCHAR(80) NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    saved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, playlist_id)
);

CREATE INDEX playlist_bookmarks_page ON playlist_bookmarks (user_id, saved_at DESC, playlist_id DESC);
