package com.boongboongus.backend.domain.auth.dto;

import java.time.Instant;

public record ReauthResponse(String reauthToken, Instant expiresAt) {
}
