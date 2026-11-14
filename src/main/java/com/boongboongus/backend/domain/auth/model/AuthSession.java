package com.boongboongus.backend.domain.auth.model;

import java.time.Instant;

public record AuthSession(
        String id,
        String provider,
        String purpose,
        String redirectUri,
        String codeChallenge,
        String state,
        Instant expiresAt) {
}
