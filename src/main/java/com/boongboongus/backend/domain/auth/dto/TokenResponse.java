package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

public record TokenResponse(String accessToken, int accessTokenExpiresInSeconds, String refreshToken,
        int refreshTokenExpiresInSeconds, String tokenType, User user, boolean isNewUser) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record User(String id, String nickname, LocalDate birthDate, String profileImageUrl,
            String provider, boolean onboardingCompleted, String gender) {
    }
}
