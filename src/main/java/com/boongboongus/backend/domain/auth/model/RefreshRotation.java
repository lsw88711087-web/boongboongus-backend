package com.boongboongus.backend.domain.auth.model;

import java.time.Instant;

public record RefreshRotation(String requestKey, Instant retryUntil, String sealedResponse) {
}
