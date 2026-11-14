package com.boongboongus.backend.domain.radio.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RadioRequest(@NotNull Mode mode, String story,
        @Size(min = 1, max = 80) String driveId) {
    public enum Mode { USER_STORY, RANDOM }

    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown radio request field");
    }
}
