package com.boongboongus.backend.domain.couple.repository;

import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse.Member;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoupleRepository {
    private final JdbcClient jdbc;

    CoupleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Connection> findLatest(String userId) {
        return jdbc.sql("""
                SELECT c.id, c.start_date, c.version, c.mascot_name, c.disconnected_at,
                    a.id AS a_id, a.nickname AS a_nickname, a.birth_date AS a_birth_date,
                    a.profile_image_url AS a_profile_image_url,
                    b.id AS b_id, b.nickname AS b_nickname, b.birth_date AS b_birth_date,
                    b.profile_image_url AS b_profile_image_url
                FROM couples c
                JOIN auth_users a ON a.id = c.first_member_id
                JOIN auth_users b ON b.id = c.second_member_id
                WHERE :userId IN (c.first_member_id, c.second_member_id)
                ORDER BY CASE WHEN c.disconnected_at IS NULL THEN 0 ELSE 1 END,
                    c.disconnected_at DESC, c.id
                LIMIT 1
                """).param("userId", userId).query((row, index) -> {
                    boolean connected = row.getObject("disconnected_at") == null;
                    return new Connection(row.getString("id"), row.getObject("start_date", LocalDate.class),
                            row.getInt("version"), row.getString("mascot_name"), connected,
                            connected ? member(row, "a_") : null, connected ? member(row, "b_") : null);
                }).optional();
    }

    public boolean hasActiveConnection(String first, String second) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM couples WHERE disconnected_at IS NULL
                    AND (first_member_id IN (:first, :second) OR second_member_id IN (:first, :second)))
                """).param("first", first).param("second", second).query(Boolean.class).single();
    }

    public Optional<String> findLatestDisconnectedPair(String first, String second) {
        return jdbc.sql("""
                SELECT id FROM couples WHERE disconnected_at IS NOT NULL
                    AND ((first_member_id = :first AND second_member_id = :second)
                      OR (first_member_id = :second AND second_member_id = :first))
                ORDER BY disconnected_at DESC, id
                LIMIT 1
                """).param("first", first).param("second", second).query(String.class).optional();
    }

    public void restore(String id) {
        jdbc.sql("UPDATE couples SET disconnected_at = NULL, version = version + 1 WHERE id = :id")
                .param("id", id).update();
    }

    public Candidates getCandidates(String userId) {
        return jdbc.sql("SELECT couple_start_date, mascot_name FROM auth_users WHERE id = :id").param("id", userId)
                .query((row, index) -> new Candidates(row.getObject("couple_start_date", LocalDate.class),
                        row.getString("mascot_name"))).single();
    }

    public void create(String id, String first, String second, LocalDate start, String mascot) {
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :first, :second, :start, :mascot)
                """).param("id", id).param("first", first).param("second", second).param("start", start)
                .param("mascot", mascot).update();
    }

    public record Candidates(LocalDate startDate, String mascotName) {
    }

    public boolean lockMembership(String id, String userId) {
        return jdbc.sql("""
                SELECT id FROM couples WHERE id = :id
                    AND :userId IN (first_member_id, second_member_id) FOR UPDATE
                """).param("id", id).param("userId", userId).query(String.class).optional().isPresent();
    }

    public boolean updateStartDate(String id, LocalDate startDate, int expectedVersion) {
        return jdbc.sql("""
                UPDATE couples SET start_date = :startDate, version = version + 1
                WHERE id = :id AND disconnected_at IS NULL AND version = :version
                """).param("id", id).param("startDate", startDate).param("version", expectedVersion).update() == 1;
    }

    public boolean updateMascotName(String id, String name, int expectedVersion) {
        return jdbc.sql("""
                UPDATE couples SET mascot_name = :name, version = version + 1
                WHERE id = :id AND disconnected_at IS NULL AND version = :version
                """).param("id", id).param("name", name).param("version", expectedVersion).update() == 1;
    }

    public void disconnect(String id) {
        jdbc.sql("UPDATE couples SET disconnected_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = :id")
                .param("id", id).update();
    }

    private static Member member(java.sql.ResultSet row, String prefix) throws java.sql.SQLException {
        return new Member(row.getString(prefix + "id"), row.getString(prefix + "nickname"),
                row.getObject(prefix + "birth_date", LocalDate.class), row.getString(prefix + "profile_image_url"),
                "KAKAO");
    }

    public record Connection(String id, LocalDate startDate, int version, String mascotName, boolean connected,
            Member first, Member second) {}
}
