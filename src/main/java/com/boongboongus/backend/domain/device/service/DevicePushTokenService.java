package com.boongboongus.backend.domain.device.service;

import com.boongboongus.backend.domain.device.dto.PushTokenRequest;
import com.boongboongus.backend.domain.device.repository.DevicePushTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DevicePushTokenService {
    private final DevicePushTokenRepository devices;

    DevicePushTokenService(DevicePushTokenRepository devices) {
        this.devices = devices;
    }

    public void register(String deviceId, String userId, PushTokenRequest request) {
        if (!devices.register(deviceId, userId, request.token(), request.platform().name(), request.enabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public void unregister(String deviceId, String userId) {
        devices.deleteOwnedRegistration(deviceId, userId);
    }
}
