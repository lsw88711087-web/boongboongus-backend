package com.boongboongus.backend.domain.device.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.domain.device.dto.PushTokenRequest;
import com.boongboongus.backend.domain.device.service.DevicePushTokenService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/push-token")
class DevicePushTokenController {
    private final DevicePushTokenService devices;

    DevicePushTokenController(DevicePushTokenService devices) {
        this.devices = devices;
    }

    @PutMapping
    @Operation(summary = "현재 기기 푸시 토큰 등록")
    ApiResponse<Accepted> register(Principal principal, @PathVariable String deviceId,
            @Valid @RequestBody PushTokenRequest request) {
        validateId(deviceId);
        devices.register(deviceId, principal.getName(), request);
        return ApiResponse.success(new Accepted(true));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "현재 계정의 기기 푸시 등록 해제")
    void unregister(Principal principal, @PathVariable String deviceId) {
        validateId(deviceId);
        devices.unregister(deviceId, principal.getName());
    }

    private static void validateId(String deviceId) {
        if (deviceId.isBlank() || deviceId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    record Accepted(boolean accepted) {}
}
