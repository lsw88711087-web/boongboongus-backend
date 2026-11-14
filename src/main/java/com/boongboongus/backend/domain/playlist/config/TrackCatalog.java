package com.boongboongus.backend.domain.playlist.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.dj")
public record TrackCatalog(List<@Valid Song> tracks) {
    public TrackCatalog {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    public record Song(@NotBlank String id, @NotBlank String title, @NotBlank String artist,
            @Positive int durationSeconds, List<String> moods, List<String> weather, List<String> genres,
            @NotBlank String providerTrackId, String albumImageUrl, @NotBlank String providerUri, @NotBlank String externalUrl) {
        public Song {
            moods = moods == null ? List.of() : List.copyOf(moods);
            weather = weather == null ? List.of() : List.copyOf(weather);
            genres = genres == null ? List.of() : List.copyOf(genres);
        }
    }
}
