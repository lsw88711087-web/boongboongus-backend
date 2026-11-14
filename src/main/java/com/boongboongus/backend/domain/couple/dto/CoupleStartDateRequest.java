package com.boongboongus.backend.domain.couple.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record CoupleStartDateRequest(@NotNull LocalDate startDate, @NotNull @Positive Integer expectedVersion) {
    @JsonAnySetter
    void unknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown start date field");
    }
}
