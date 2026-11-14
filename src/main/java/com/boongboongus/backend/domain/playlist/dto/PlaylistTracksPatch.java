package com.boongboongus.backend.domain.playlist.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlaylistTracksPatch(@NotEmpty List<@NotBlank @Size(max = 80) String> trackIds,
        @NotNull @Positive Integer expectedVersion) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown playlist edit field");
    }
}
