package com.boongboongus.backend.domain.notification.repository;

import com.boongboongus.backend.domain.notification.dto.NotificationResponse;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Sender;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.TargetType;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Type;
import com.boongboongus.backend.domain.notification.model.NotificationCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private static final String SELECT = """
            SELECT n.id, n.type, n.title, n.body, n.target_type, n.target_id, n.created_at, n.expires_at,
                n.read_at, u.id AS sender_id, u.nickname, u.birth_date, u.profile_image_url
            FROM notifications n LEFT JOIN auth_users u ON u.id = n.sender_user_id
            WHERE n.recipient_user_id = :recipient AND n.expires_at > :now
            """;

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<NotificationResponse> list(String userId, Instant now, boolean unreadOnly, int limit,
            NotificationCursor cursor) {
        String unread = unreadOnly ? " AND n.read_at IS NULL" : "";
        String after = cursor == null ? "" :
                " AND (n.created_at < :created OR (n.created_at = :created AND n.id < :cursorId))";
        var query = jdbc.sql(SELECT + unread + after + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit")
                .param("recipient", userId).param("now", now.atOffset(ZoneOffset.UTC)).param("limit", limit);
        if (cursor != null) {
            query.param("created", cursor.createdAt().atOffset(ZoneOffset.UTC)).param("cursorId", cursor.id());
        }
        return query.query(NotificationRepository::map).list();
    }

    public Optional<NotificationResponse> markRead(String id, String userId, Instant now) {
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        int updated = jdbc.sql("""
                UPDATE notifications SET read_at = COALESCE(read_at, :now)
                WHERE id = :id AND recipient_user_id = :recipient AND expires_at > :now
                """).param("id", id).param("recipient", userId).param("now", timestamp).update();
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " AND n.id = :id")
                .param("recipient", userId).param("now", timestamp).param("id", id)
                .query(NotificationRepository::map).optional();
    }

    public void insert(String id, String eventKey, String senderId, String recipientId, Type type,
            String title, String body, TargetType targetType, String targetId, Instant now, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO notifications (id, event_key, sender_user_id, recipient_user_id, type, title, body,
                    target_type, target_id, created_at, expires_at)
                VALUES (:id, :event, :sender, :recipient, :type, :title, :body, :targetType, :target, :now, :expiry)
                ON CONFLICT DO NOTHING
                """).param("id", id).param("event", eventKey).param("sender", senderId)
                .param("recipient", recipientId).param("type", type.name()).param("title", title)
                .param("body", body).param("targetType", targetType.name()).param("target", targetId)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("expiry", expiresAt.atOffset(ZoneOffset.UTC)).update();
    }

    public int countUnread(String userId, Instant now) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_user_id = :recipient AND expires_at > :now AND read_at IS NULL
                """).param("recipient", userId).param("now", now.atOffset(ZoneOffset.UTC)).query(Integer.class).single();
    }

    public void deleteExpired(Instant now) {
        jdbc.sql("DELETE FROM notifications WHERE expires_at <= :now")
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    private static NotificationResponse map(ResultSet row, int index) throws SQLException {
        String senderId = row.getString("sender_id");
        Sender sender = senderId == null ? null : new Sender(senderId, row.getString("nickname"),
                row.getObject("birth_date", LocalDate.class), row.getString("profile_image_url"), "KAKAO");
        OffsetDateTime readAt = row.getObject("read_at", OffsetDateTime.class);
        return new NotificationResponse(row.getString("id"), Type.valueOf(row.getString("type")), sender,
                row.getString("title"), row.getString("body"), TargetType.valueOf(row.getString("target_type")),
                row.getString("target_id"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(), readAt == null ? null : readAt.toInstant());
    }
}
