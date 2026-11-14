package com.boongboongus.backend.domain.auth.dto;

import com.boongboongus.backend.domain.legal.LegalDocumentResponse.Type;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record OnboardingRequest(@NotBlank String nickname,
        @JsonProperty(value = "birthDate", required = true) LocalDate birthDate,
        @NotNull List<@NotNull @Valid Consent> consents, Gender gender, LocalDate coupleStartDate,
        @JsonProperty("mascotName") String mascotName) {
    public enum Gender { MALE, FEMALE }

    public record Consent(@NotNull Type type, @NotBlank String version, @NotNull Boolean agreed) {
        @JsonAnySetter
        void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown consent field");
        }
    }

    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown onboarding field");
    }
}
