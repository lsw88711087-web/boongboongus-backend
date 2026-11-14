package com.boongboongus.backend.domain.radio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RadioEpisode(String id, String driveId, RadioRequest.Mode mode, String story, String broadcastText,
        String audioUrl, Instant audioUrlExpiresAt, Integer durationSeconds, String status, Instant createdAt) {}
