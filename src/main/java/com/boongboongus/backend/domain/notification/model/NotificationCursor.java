package com.boongboongus.backend.domain.notification.model;

import java.time.Instant;

public record NotificationCursor(Instant createdAt, String id) {
}
