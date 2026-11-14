package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(@NotBlank String refreshToken) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown refresh request field");
    }
}
