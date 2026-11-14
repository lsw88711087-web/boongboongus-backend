package com.boongboongus.backend.domain.device.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PushTokenRequest(@NotBlank String token, @NotNull Platform platform, @NotNull Boolean enabled) {
    public enum Platform { IOS, ANDROID, WEB }

    @JsonAnySetter
    void unknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown push registration field");
    }
}
