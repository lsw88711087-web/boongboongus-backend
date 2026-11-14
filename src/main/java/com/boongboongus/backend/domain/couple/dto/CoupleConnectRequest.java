package com.boongboongus.backend.domain.couple.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CoupleConnectRequest(@NotBlank @Size(max = 80) String code) {
    @JsonAnySetter
    void unknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown couple connection field");
    }
}
