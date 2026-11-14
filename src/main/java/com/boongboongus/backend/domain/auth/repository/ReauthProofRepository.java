package com.boongboongus.backend.domain.auth.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReauthProofRepository {
    private final JdbcClient jdbc;

    ReauthProofRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isValidForDeletion(String hash, String userId, Instant now) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM auth_reauth_proofs WHERE token_hash = :hash AND user_id = :userId
                    AND purpose = 'ACCOUNT_DELETION' AND consumed_at IS NULL AND expires_at > :now
                )
                """).param("hash", hash).param("userId", userId)
                .param("now", now.atOffset(ZoneOffset.UTC)).query(Boolean.class).single();
    }

    public void save(String hash, String userId, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO auth_reauth_proofs (token_hash, user_id, purpose, expires_at)
                VALUES (:hash, :userId, 'ACCOUNT_DELETION', :expiresAt)
                """).param("hash", hash).param("userId", userId)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC)).update();
    }
}
