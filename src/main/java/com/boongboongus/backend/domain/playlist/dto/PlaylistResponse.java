package com.boongboongus.backend.domain.playlist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record PlaylistResponse(String id, String title, List<String> moods, String weather, List<String> genres,
        int targetDurationSeconds, List<Track> tracks, int trackCount, int durationSeconds,
        boolean savedByMe, Instant createdAt, int version) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Track(String id, String providerTrackId, String title, String artist, int durationSeconds,
            String albumImageUrl, String providerUri, String externalUrl, int order) {
    }
}
