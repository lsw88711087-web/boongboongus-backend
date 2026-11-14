package com.boongboongus.backend.domain.playlist.repository;

import com.boongboongus.backend.domain.playlist.dto.PlaylistRequest;
import com.boongboongus.backend.domain.playlist.dto.PlaylistResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PlaylistRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    PlaylistRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void queue(String jobId, PlaylistRequest request) {
        jdbc.sql("INSERT INTO playlist_requests (job_id, request_snapshot) VALUES (:id, :request)")
                .param("id", jobId).param("request", json.writeValueAsString(request)).update();
    }

    public List<Pending> pending() {
        return jdbc.sql("""
                SELECT j.id, j.owner_user_id, r.request_snapshot FROM ai_jobs j
                JOIN playlist_requests r ON r.job_id = j.id
                WHERE j.status = 'QUEUED' AND j.expires_at > CURRENT_TIMESTAMP
                ORDER BY j.created_at, j.id LIMIT 16
                """).query((row, index) -> new Pending(row.getString("id"), row.getString("owner_user_id"),
                        json.readValue(row.getString("request_snapshot"), PlaylistRequest.class))).list();
    }

    public void save(String owner, String driveId, PlaylistResponse playlist) {
        jdbc.sql("""
                INSERT INTO playlists (id, owner_user_id, drive_id, result_snapshot, created_at)
                VALUES (:id, :owner, :drive, :result, :created)
                """).param("id", playlist.id()).param("owner", owner).param("drive", driveId, java.sql.Types.VARCHAR)
                .param("result", json.writeValueAsString(playlist))
                .param("created", OffsetDateTime.ofInstant(playlist.createdAt(), ZoneOffset.UTC)).update();
    }

    public Optional<Stored> find(String id) {
        return find(id, false);
    }

    public Optional<Stored> lock(String id) {
        return find(id, true);
    }

    public void update(PlaylistResponse playlist) {
        jdbc.sql("UPDATE playlists SET result_snapshot = :snapshot WHERE id = :id")
                .param("snapshot", json.writeValueAsString(playlist)).param("id", playlist.id()).update();
    }

    private Optional<Stored> find(String id, boolean lock) {
        String sql = "SELECT owner_user_id, drive_id, result_snapshot FROM playlists WHERE id = :id";
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .param("id", id).query((row, index) -> new Stored(row.getString("owner_user_id"),
                        row.getString("drive_id"),
                        json.readValue(row.getString("result_snapshot"), PlaylistResponse.class))).optional();
    }

    public void bookmark(String userId, String playlistId) {
        jdbc.sql("""
                INSERT INTO playlist_bookmarks (user_id, playlist_id) VALUES (:user, :playlist)
                ON CONFLICT DO NOTHING
                """).param("user", userId).param("playlist", playlistId).update();
    }

    public void removeBookmark(String userId, String playlistId) {
        jdbc.sql("DELETE FROM playlist_bookmarks WHERE user_id = :user AND playlist_id = :playlist")
                .param("user", userId).param("playlist", playlistId).update();
    }

    public boolean bookmarked(String userId, String playlistId) {
        return jdbc.sql("SELECT COUNT(*) FROM playlist_bookmarks WHERE user_id = :user AND playlist_id = :playlist")
                .param("user", userId).param("playlist", playlistId).query(Long.class).single() > 0;
    }

    private static final String ACCESSIBLE_BOOKMARKS = """
            FROM playlist_bookmarks b
            JOIN playlists p ON p.id = b.playlist_id
            LEFT JOIN drives d ON d.id = p.drive_id
            WHERE b.user_id = :user AND (p.owner_user_id = :user OR (
                d.status <> 'CANCELLED' AND :user IN (d.driver_user_id, d.passenger_user_id)
                AND EXISTS (SELECT 1 FROM couples c WHERE c.id = d.couple_id AND c.disconnected_at IS NULL
                    AND :user IN (c.first_member_id, c.second_member_id))))
            """;

    public long bookmarkCount(String userId) {
        return jdbc.sql("SELECT COUNT(*) " + ACCESSIBLE_BOOKMARKS).param("user", userId).query(Long.class).single();
    }

    public List<Bookmarked> bookmarks(String userId, OffsetDateTime savedAt, String playlistId, int limit) {
        String boundary = savedAt == null ? "" :
                " AND (b.saved_at < :saved OR (b.saved_at = :saved AND b.playlist_id < :playlist))";
        var query = jdbc.sql("SELECT p.result_snapshot, p.drive_id, d.title AS drive_title, b.saved_at "
                + ACCESSIBLE_BOOKMARKS + boundary + " ORDER BY b.saved_at DESC, b.playlist_id DESC LIMIT :limit")
                .param("user", userId).param("limit", limit);
        if (savedAt != null) {
            query.param("saved", savedAt).param("playlist", playlistId);
        }
        return query.query((row, index) -> new Bookmarked(
                json.readValue(row.getString("result_snapshot"), PlaylistResponse.class),
                row.getString("drive_id"), row.getString("drive_title"),
                row.getObject("saved_at", OffsetDateTime.class))).list();
    }

    public record Bookmarked(PlaylistResponse playlist, String driveId, String driveTitle, OffsetDateTime savedAt) {}

    public record Pending(String jobId, String owner, PlaylistRequest request) {}
    public record Stored(String owner, String driveId, PlaylistResponse result) {}
}
