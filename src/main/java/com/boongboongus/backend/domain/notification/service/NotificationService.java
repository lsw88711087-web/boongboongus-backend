package com.boongboongus.backend.domain.notification.service;

import com.boongboongus.backend.domain.notification.dto.NotificationResponse;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Page;
import com.boongboongus.backend.domain.notification.model.NotificationCursor;
import com.boongboongus.backend.domain.notification.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.boongboongus.backend.domain.home.PlaceShareNotification;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Type;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.TargetType;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final NotificationRepository notifications;

    NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    PlaceShareNotification.Receipt placeShared(String sender, String recipient, String placeId) {
        String id = "ntf_" + UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notifications.insert(id, id, sender, recipient, Type.PLACE_SHARED, "장소 공유",
                "함께 가고 싶은 장소를 확인해 보세요.", TargetType.PLACE, placeId, now, now.plus(30, ChronoUnit.DAYS));
        return new PlaceShareNotification.Receipt(id, now);
    }

    public void coupleChanged(String sender, String recipient, String coupleId, int version, boolean connected) {
        Type type = connected ? Type.COUPLE_CONNECTED : Type.COUPLE_DISCONNECTED;
        String title = connected ? "커플이 연결됐어요" : "커플 연결이 해제됐어요";
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notifications.insert("ntf_" + UUID.randomUUID(), type + ":" + coupleId + ":" + version,
                sender, recipient, type, title, "커플 상태를 확인해 보세요.", TargetType.COUPLE, coupleId,
                now, now.plus(30, ChronoUnit.DAYS));
    }

    public int countUnread(String userId) {
        return notifications.countUnread(userId, Instant.now());
    }

    public Page list(String userId, String cursor, int limit, boolean unreadOnly) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var items = notifications.list(userId, Instant.now(), unreadOnly, limit + 1,
                decode(cursor, userId, unreadOnly));
        if (items.size() <= limit) {
            return new Page(items, null, false);
        }
        var page = items.subList(0, limit);
        var last = page.getLast();
        String value = userId + "\n" + unreadOnly + "\n" + last.createdAt() + "\n" + last.id();
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return new Page(page, next, true);
    }

    @Transactional
    public NotificationResponse markRead(String userId, String id) {
        return notifications.markRead(id, userId, Instant.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Scheduled(fixedDelay = 3600000)
    void deleteExpired() {
        notifications.deleteExpired(Instant.now());
    }

    private static NotificationCursor decode(String cursor, String userId, boolean unreadOnly) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.length() <= 512) {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\n", -1);
                if (parts.length == 4 && userId.equals(parts[0])
                        && Boolean.toString(unreadOnly).equals(parts[1])
                        && !parts[3].isBlank() && parts[3].length() <= 80) {
                    return new NotificationCursor(Instant.parse(parts[2]), parts[3]);
                }
            }
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
