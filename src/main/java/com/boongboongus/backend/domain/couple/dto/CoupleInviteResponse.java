package com.boongboongus.backend.domain.couple.dto;

import java.time.Instant;

public record CoupleInviteResponse(String inviteId, String code, Instant expiresAt, Status status) {
    public enum Status { ACTIVE }
}
