package com.boongboongus.backend.domain.couple.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CurrentCoupleResponse(Couple couple, Status connectionStatus) {
    public enum Status { UNLINKED, CONNECTED, DISCONNECTED }

    public record Couple(String id, Status status, List<Member> members, LocalDate startDate,
            long daysTogether, long cumulativeDistanceMeters, long completedCardCount, int version,
            String mascotName) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Member(String id, String nickname, LocalDate birthDate, String profileImageUrl, String provider) {}
}
