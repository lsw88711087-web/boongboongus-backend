package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;

public record                                                                                                                           AuthorizationRequest(
        @NotNull @Pattern(regexp = "KAKAO") String provider,
        @NotNull URI redirectUri,
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String codeChallenge,
        @NotNull @Pattern(regexp = "LOGIN") String purpose) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown authorization request field");
    }
}
