package com.boongboongus.backend.domain.radio.repository;

import com.boongboongus.backend.domain.radio.dto.RadioRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RadioRepository {
    private final JdbcClient jdbc;

    RadioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String jobId, String owner, RadioRequest request) {
        jdbc.sql("""
                INSERT INTO radio_episodes (job_id, owner_user_id, drive_id, mode, story)
                VALUES (:job, :owner, :drive, :mode, :story)
                """).param("job", jobId).param("owner", owner).param("drive", request.driveId(), Types.VARCHAR)
                .param("mode", request.mode().name()).param("story", request.story(), Types.VARCHAR).update();
    }

    public List<Pending> pending() {
        return jdbc.sql("""
                SELECT r.job_id, r.owner_user_id, r.drive_id, r.mode, r.story
                FROM radio_episodes r JOIN ai_jobs j ON j.id = r.job_id
                WHERE j.status = 'QUEUED' AND j.expires_at > CURRENT_TIMESTAMP
                ORDER BY j.created_at, j.id LIMIT 8
                """).query((row, index) -> new Pending(row.getString("job_id"), row.getString("owner_user_id"),
                        new RadioRequest(RadioRequest.Mode.valueOf(row.getString("mode")), row.getString("story"),
                                row.getString("drive_id")))).list();
    }

    public void ready(String jobId, String script, byte[] audio) {
        jdbc.sql("UPDATE radio_episodes SET broadcast_text = :script, audio = :audio WHERE job_id = :job")
                .param("script", script).param("audio", audio).param("job", jobId).update();
    }

    public Optional<Episode> find(String owner, String id) {
        return jdbc.sql("""
                SELECT j.resource_id, r.drive_id, r.mode, r.story, r.broadcast_text, j.created_at
                FROM radio_episodes r JOIN ai_jobs j ON j.id = r.job_id
                WHERE j.resource_id = :id AND j.status = 'SUCCEEDED' AND r.owner_user_id = :owner
                """).param("id", id).param("owner", owner).query(RadioRepository::episode).optional();
    }

    public List<Episode> history(String owner, String query, OffsetDateTime before, String beforeId, int limit) {
        return jdbc.sql("""
                SELECT j.resource_id, r.drive_id, r.mode, r.story, r.broadcast_text, j.created_at
                FROM radio_episodes r JOIN ai_jobs j ON j.id = r.job_id
                WHERE r.owner_user_id = :owner AND r.mode = 'USER_STORY' AND j.status = 'SUCCEEDED'
                  AND (:query = '' OR POSITION(LOWER(:query) IN LOWER(r.story)) > 0
                                   OR POSITION(LOWER(:query) IN LOWER(r.broadcast_text)) > 0)
                  AND (CAST(:before AS TIMESTAMP WITH TIME ZONE) IS NULL OR j.created_at < :before
                       OR (j.created_at = :before AND j.resource_id < :beforeId))
                ORDER BY j.created_at DESC, j.resource_id DESC
                LIMIT :limit
                """).param("owner", owner).param("query", query)
                .param("before", before, Types.TIMESTAMP_WITH_TIMEZONE).param("beforeId", beforeId, Types.VARCHAR)
                .param("limit", limit).query(RadioRepository::episode).list();
    }

    private static Episode episode(ResultSet row, int index) throws SQLException {
        return new Episode(row.getString("resource_id"), row.getString("drive_id"),
                RadioRequest.Mode.valueOf(row.getString("mode")), row.getString("story"),
                row.getString("broadcast_text"), row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public Optional<byte[]> audio(String owner, String id) {
        return jdbc.sql("""
                SELECT r.audio FROM radio_episodes r JOIN ai_jobs j ON j.id = r.job_id
                WHERE j.resource_id = :id AND j.status = 'SUCCEEDED' AND r.owner_user_id = :owner
                """).param("id", id).param("owner", owner).query((row, index) -> row.getBytes("audio")).optional();
    }

    public record Pending(String jobId, String owner, RadioRequest request) {}
    public record Episode(String id, String driveId, RadioRequest.Mode mode, String story, String script, Instant createdAt) {}
}
