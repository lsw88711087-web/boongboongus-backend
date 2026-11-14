package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LogoutRequest(@NotBlank String refreshToken, @NotBlank @Size(max = 80) String deviceId) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown logout request field");
    }
}
