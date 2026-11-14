package com.boongboongus.backend.domain.auth.dto;

import java.net.URI;
import java.time.Instant;

public record AuthorizationResponse(String authSessionId, URI authorizationUrl, String state, Instant expiresAt) {
}
