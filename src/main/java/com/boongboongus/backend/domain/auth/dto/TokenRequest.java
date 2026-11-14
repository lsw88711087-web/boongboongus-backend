package com.boongboongus.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TokenRequest(
        @NotBlank @Size(max = 80) String authSessionId,
        @NotBlank String authorizationCode,
        @NotBlank String state,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}") String codeVerifier) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown token request field");
    }
}
