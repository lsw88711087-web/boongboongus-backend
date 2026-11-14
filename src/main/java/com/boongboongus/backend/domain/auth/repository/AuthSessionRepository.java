package com.boongboongus.backend.domain.auth.repository;

import com.boongboongus.backend.domain.auth.model.AuthSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthSessionRepository {
    private final JdbcClient jdbc;

    AuthSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Attempt> lockById(String id) {
        return jdbc.sql("""
                SELECT id, provider, purpose, redirect_uri, code_challenge, state, expires_at, consumed_at, user_id
                FROM auth_sessions WHERE id = :id FOR UPDATE
                """).param("id", id).query((row, index) -> new Attempt(new AuthSession(
                        row.getString("id"), row.getString("provider"), row.getString("purpose"),
                        row.getString("redirect_uri"), row.getString("code_challenge"), row.getString("state"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()),
                        row.getObject("consumed_at") != null, row.getString("user_id"))).optional();
    }

    public void consume(String id, Instant now) {
        jdbc.sql("UPDATE auth_sessions SET consumed_at = :now WHERE id = :id")
                .param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    public record Attempt(AuthSession session, boolean consumed, String userId) {}

    public void save(AuthSession session) {
        save(session, null);
    }

    public void save(AuthSession session, String userId) {
        jdbc.sql("""
                INSERT INTO auth_sessions (id, provider, purpose, redirect_uri, code_challenge, state, expires_at, user_id)
                VALUES (:id, :provider, :purpose, :redirectUri, :codeChallenge, :state, :expiresAt, :userId)
                """)
                .param("id", session.id())
                .param("provider", session.provider())
                .param("purpose", session.purpose())
                .param("redirectUri", session.redirectUri())
                .param("codeChallenge", session.codeChallenge())
                .param("state", session.state())
                .param("userId", userId, java.sql.Types.VARCHAR)
                .param("expiresAt", session.expiresAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
