package com.boongboongus.backend.domain.couple.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CoupleMascotNameRequest(@NotBlank String name, @NotNull @Positive Integer expectedVersion) {
    @JsonAnySetter
    void unknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown mascot name field");
    }
}
