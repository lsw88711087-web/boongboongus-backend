package com.boongboongus.backend.domain.auth.model;

import java.time.Instant;

public record ServiceSession(String id, String userId, String refreshTokenHash, Instant expiresAt) {
}
