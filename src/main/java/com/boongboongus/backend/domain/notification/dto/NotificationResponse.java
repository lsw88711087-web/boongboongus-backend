package com.boongboongus.backend.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationResponse(String id, Type type, Sender sender, String title, String body,
        TargetType targetType, String targetId, Instant createdAt, Instant expiresAt, Instant readAt) {
    public enum Type { PLACE_SHARED, REFLECTION_SUBMITTED, COUPLE_CONNECTED, COUPLE_DISCONNECTED }

    public enum TargetType { PLACE, CARD, COUPLE }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sender(String id, String nickname, LocalDate birthDate, String profileImageUrl, String provider) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<NotificationResponse> items, String nextCursor, boolean hasMore) {
    }
}
