package com.boongboongus.backend.domain.auth.repository;

import com.boongboongus.backend.domain.auth.model.ServiceSession;
import com.boongboongus.backend.domain.auth.model.RefreshRotation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ServiceSessionRepository {
    private final JdbcClient jdbc;

    ServiceSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findIdByRefreshHash(String hash) {
        return jdbc.sql("""
                SELECT id FROM auth_service_sessions WHERE refresh_token_hash = :hash
                UNION
                SELECT session_id FROM auth_refresh_rotations WHERE refresh_hash = :hash
                """).param("hash", hash).query(String.class).optional();
    }

    public Optional<StoredSession> lockById(String id) {
        return jdbc.sql("""
                SELECT id, user_id, refresh_token_hash, expires_at, revoked_at
                FROM auth_service_sessions WHERE id = :id FOR UPDATE
                """).param("id", id).query((row, index) -> new StoredSession(new ServiceSession(
                        row.getString("id"), row.getString("user_id"), row.getString("refresh_token_hash"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()),
                        row.getObject("revoked_at") != null)).optional();
    }

    public Optional<RefreshRotation> findRotation(String hash) {
        return jdbc.sql("""
                SELECT request_key, retry_until, sealed_response
                FROM auth_refresh_rotations WHERE refresh_hash = :hash
                """).param("hash", hash).query((row, index) -> new RefreshRotation(row.getString("request_key"),
                        row.getObject("retry_until", OffsetDateTime.class).toInstant(),
                        row.getString("sealed_response"))).optional();
    }

    public boolean hasRotationKey(String id, String key) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM auth_refresh_rotations WHERE session_id = :id AND request_key = :key
                """).param("id", id).param("key", key).query(Long.class).single() > 0;
    }

    public void recordRotation(String id, String hash, String key, Instant retryUntil, String response) {
        jdbc.sql("""
                INSERT INTO auth_refresh_rotations (refresh_hash, session_id, request_key, retry_until, sealed_response)
                VALUES (:hash, :id, :key, :until, :response)
                """).param("hash", hash).param("id", id).param("key", key)
                .param("until", retryUntil.atOffset(ZoneOffset.UTC)).param("response", response).update();
    }

    public void rotate(ServiceSession session) {
        jdbc.sql("""
                UPDATE auth_service_sessions SET refresh_token_hash = :hash, expires_at = :expires
                WHERE id = :id
                """).param("hash", session.refreshTokenHash())
                .param("expires", session.expiresAt().atOffset(ZoneOffset.UTC)).param("id", session.id()).update();
    }

    public void revoke(String id, Instant now) {
        jdbc.sql("UPDATE auth_service_sessions SET revoked_at = :now WHERE id = :id")
                .param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).update();
        jdbc.sql("UPDATE auth_refresh_rotations SET sealed_response = NULL WHERE session_id = :id")
                .param("id", id).update();
    }

    public record StoredSession(ServiceSession session, boolean revoked) {}

    public void save(ServiceSession session) {
        jdbc.sql("""
                INSERT INTO auth_service_sessions (id, user_id, refresh_token_hash, expires_at)
                VALUES (:id, :userId, :refreshHash, :expiresAt)
                """).param("id", session.id()).param("userId", session.userId())
                .param("refreshHash", session.refreshTokenHash())
                .param("expiresAt", session.expiresAt().atOffset(ZoneOffset.UTC)).update();
    }
}
