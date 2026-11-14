package com.boongboongus.backend.domain.playlist.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlaylistRequest(@Size(min = 1, max = 80) String driveId,
        @NotNull @Size(min = 1, max = 2) List<@NotBlank String> moods,
        @NotBlank String weather, @Size(max = 3) List<@NotBlank String> genres) {
    @JsonAnySetter
    void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown playlist request field");
    }
}
