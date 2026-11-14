package com.boongboongus.backend.domain.playlist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PlaylistLibraryResponse(List<Item> items, String nextCursor, boolean hasMore, long totalCount) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String id, String title, int trackCount, int durationSeconds, String thumbnailUrl,
            String driveId, String driveTitle) {}
}
