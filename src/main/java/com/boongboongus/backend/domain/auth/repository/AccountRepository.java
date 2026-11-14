package com.boongboongus.backend.domain.auth.repository;

import com.boongboongus.backend.domain.auth.dto.TokenResponse.User;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account findOrCreateKakaoUser(long kakaoId) {
        boolean created = jdbc.sql("""
                INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed)
                VALUES (:id, :kakaoId, '', FALSE) ON CONFLICT DO NOTHING
                """).param("id", "usr_" + UUID.randomUUID()).param("kakaoId", kakaoId).update() == 1;

        User user = selectBy("kakao_id = :value", kakaoId).orElseThrow();
        return new Account(user, created);
    }

    public void completeOnboarding(String id, String nickname, LocalDate birthDate, LocalDate start,
            String mascot, String gender) {
        int updated = jdbc.sql("""
                UPDATE auth_users SET nickname = :nickname, birth_date = :birthDate, couple_start_date = :start,
                    mascot_name = :mascot, gender = :gender, onboarding_completed = TRUE WHERE id = :id
                """).param("id", id).param("nickname", nickname).param("birthDate", birthDate)
                .param("start", start).param("mascot", mascot).param("gender", gender).update();
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
    }

    public void saveConsent(String id, String type, String version) {
        jdbc.sql("""
                INSERT INTO auth_user_consents (user_id, document_type, document_version, agreed_at)
                VALUES (:id, :type, :version, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING
                """).param("id", id).param("type", type).param("version", version).update();
    }

    public void updateNickname(String id, String nickname) {
        jdbc.sql("UPDATE auth_users SET nickname = :nickname WHERE id = :id")
                .param("id", id).param("nickname", nickname).update();
    }

    public void updateBirthDate(String id, LocalDate birthDate) {
        jdbc.sql("UPDATE auth_users SET birth_date = :birthDate WHERE id = :id")
                .param("id", id).param("birthDate", birthDate).update();
    }

    public Optional<Long> findKakaoId(String id) {
        return jdbc.sql("SELECT kakao_id FROM auth_users WHERE id = :id")
                .param("id", id).query(Long.class).optional();
    }

    public Optional<User> findById(String id) {
        return selectBy("id = :value", id);
    }

    private Optional<User> selectBy(String condition, Object value) {
        return jdbc.sql("""
                SELECT id, nickname, birth_date, profile_image_url, onboarding_completed, gender
                FROM auth_users WHERE
                """ + " " + condition).param("value", value)
                .query((row, index) -> new User(row.getString("id"), row.getString("nickname"),
                        row.getObject("birth_date", LocalDate.class), row.getString("profile_image_url"), "KAKAO",
                        row.getBoolean("onboarding_completed"), row.getString("gender")))
                .optional();
    }

    public record Account(User user, boolean created) {}
}
