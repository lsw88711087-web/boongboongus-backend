package com.boongboongus.backend.domain.job.repository;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.dto.JobResponse.Kind;
import com.boongboongus.backend.domain.job.dto.JobResponse.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JobRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    JobRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(String id, String owner, Kind kind, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO ai_jobs (id, owner_user_id, kind, status, created_at, expires_at)
                VALUES (:id, :owner, :kind, 'QUEUED', :created, :expires)
                """).param("id", id).param("owner", owner).param("kind", kind.name())
                .param("created", utc(createdAt)).param("expires", utc(expiresAt)).update();
    }

    public Optional<JobResponse> findOwned(String id, String owner, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.sql("SELECT * FROM ai_jobs WHERE id = :id AND owner_user_id = :owner" + suffix)
                .param("id", id).param("owner", owner).query(this::map).optional();
    }

    public boolean start(String id, Instant now) {
        return jdbc.sql("""
                UPDATE ai_jobs SET status = 'RUNNING'
                WHERE id = :id AND status = 'QUEUED' AND expires_at > :now
                """).param("id", id).param("now", utc(now)).update() == 1;
    }

    public boolean finish(String id, Instant now, String resourceId, ErrorDetail error) {
        return jdbc.sql("""
                UPDATE ai_jobs SET status = :status, resource_id = :resource, error_snapshot = :error,
                    finished_at = :now
                WHERE id = :id AND status = 'RUNNING' AND expires_at > :now
                """).param("id", id).param("now", utc(now)).param("status", error == null ? "SUCCEEDED" : "FAILED")
                .param("resource", resourceId, java.sql.Types.VARCHAR)
                .param("error", error == null ? null : json.writeValueAsString(error), java.sql.Types.VARCHAR)
                .update() == 1;
    }

    public void requestCancellation(String id) {
        jdbc.sql("UPDATE ai_jobs SET status = 'CANCEL_REQUESTED' WHERE id = :id")
                .param("id", id).update();
    }

    public void finalizeCancellations(Instant now) {
        jdbc.sql("""
                UPDATE ai_jobs SET status = 'CANCELLED', finished_at = :now
                WHERE status = 'CANCEL_REQUESTED'
                """).param("now", utc(now)).update();
    }

    private JobResponse map(ResultSet row, int index) throws SQLException {
        Kind kind = Kind.valueOf(row.getString("kind"));
        String error = row.getString("error_snapshot");
        var finishedAt = row.getObject("finished_at", OffsetDateTime.class);
        return new JobResponse(row.getString("id"), kind, Status.valueOf(row.getString("status")),
                kind.resourceType(), row.getString("resource_id"), 2,
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                error == null ? null : json.readValue(error, ErrorDetail.class));
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
