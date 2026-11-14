package com.boongboongus.backend.domain.job.dto;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobResponse(String id, Kind kind, Status status, ResourceType resourceType, String resourceId,
        int retryAfterSeconds, Instant createdAt, Instant finishedAt, Instant expiresAt, ErrorDetail error) {
    public enum Kind {
        PLAYLIST_GENERATION(ResourceType.PLAYLIST),
        RADIO_GENERATION(ResourceType.RADIO_EPISODE),
        GAME_GENERATION(ResourceType.GAME_SESSION);

        private final ResourceType resourceType;

        Kind(ResourceType resourceType) {
            this.resourceType = resourceType;
        }

        public ResourceType resourceType() {
            return resourceType;
        }
    }

    public enum ResourceType { PLAYLIST, RADIO_EPISODE, GAME_SESSION }

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED }
}
