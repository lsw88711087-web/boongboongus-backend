package com.boongboongus.backend.domain.couple.repository;

import com.boongboongus.backend.domain.couple.dto.CoupleInviteResponse;
import com.boongboongus.backend.domain.couple.dto.CoupleInviteResponse.Status;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoupleInviteRepository {
    private final JdbcClient jdbc;

    CoupleInviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockOwner(String userId) {
        return jdbc.sql("SELECT id FROM auth_users WHERE id = :id FOR UPDATE")
                .param("id", userId).query(String.class).optional().isPresent();
    }

    public Optional<CoupleInviteResponse> findActive(String userId, Instant now) {
        return jdbc.sql("""
                SELECT id, code, expires_at FROM couple_invites
                WHERE owner_user_id = :owner AND active_slot = 1 AND expires_at > :now
                """).param("owner", userId).param("now", now.atOffset(ZoneOffset.UTC))
                .query((row, index) -> new CoupleInviteResponse(row.getString("id"), row.getString("code"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(), Status.ACTIVE))
                .optional();
    }

    public void clearExpiredSlot(String userId, Instant now) {
        jdbc.sql("""
                UPDATE couple_invites SET active_slot = NULL
                WHERE owner_user_id = :owner AND active_slot = 1 AND expires_at <= :now
                """).param("owner", userId).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<String> findOwnerByCode(String code) {
        return jdbc.sql("SELECT owner_user_id FROM couple_invites WHERE code = :code")
                .param("code", code).query(String.class).optional();
    }

    public Optional<StoredInvite> findByCode(String code) {
        return jdbc.sql("SELECT id, expires_at, active_slot, consumed_at FROM couple_invites WHERE code = :code")
                .param("code", code).query((row, index) -> new StoredInvite(row.getString("id"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getObject("active_slot") != null, row.getObject("consumed_at") != null)).optional();
    }

    public void consume(String id, Instant now) {
        jdbc.sql("UPDATE couple_invites SET consumed_at = :now, active_slot = NULL WHERE id = :id")
                .param("id", id).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public void deactivateForOwner(String userId) {
        jdbc.sql("UPDATE couple_invites SET active_slot = NULL WHERE owner_user_id = :owner AND active_slot = 1")
                .param("owner", userId).update();
    }

    public record StoredInvite(String id, Instant expiresAt, boolean active, boolean consumed) {
    }

    public Optional<Boolean> findOwnedConsumption(String id, String userId) {
        return jdbc.sql("""
                SELECT consumed_at IS NOT NULL FROM couple_invites WHERE id = :id AND owner_user_id = :owner
                """).param("id", id).param("owner", userId).query(Boolean.class).optional();
    }

    public void revoke(String id) {
        jdbc.sql("UPDATE couple_invites SET active_slot = NULL WHERE id = :id").param("id", id).update();
    }

    public void save(String userId, CoupleInviteResponse invite) {
        jdbc.sql("""
                INSERT INTO couple_invites (id, owner_user_id, code, expires_at, active_slot)
                VALUES (:id, :owner, :code, :expires, 1)
                """).param("id", invite.inviteId()).param("owner", userId).param("code", invite.code())
                .param("expires", invite.expiresAt().atOffset(ZoneOffset.UTC)).update();
    }
}
