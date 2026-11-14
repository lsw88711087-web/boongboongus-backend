package com.boongboongus.backend.domain.auth.dto;

public record RefreshTokenResponse(String accessToken, int accessTokenExpiresInSeconds,
        String refreshToken, int refreshTokenExpiresInSeconds, String tokenType) {
}
