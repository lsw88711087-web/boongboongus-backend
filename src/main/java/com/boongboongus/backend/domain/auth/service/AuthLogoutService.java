package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.dto.LogoutRequest;
import com.boongboongus.backend.domain.auth.repository.ServiceSessionRepository;
import com.boongboongus.backend.domain.device.repository.DevicePushTokenRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthLogoutService {
    private final ServiceSessionRepository sessions;
    private final DevicePushTokenRepository devices;
    private final IdempotentWrite idempotency;

    AuthLogoutService(ServiceSessionRepository sessions, DevicePushTokenRepository devices,
            IdempotentWrite idempotency) {
        this.sessions = sessions;
        this.devices = devices;
        this.idempotency = idempotency;
    }

    public void logout(String key, LogoutRequest request) {
        String hash = ServiceTokens.refreshHash(request.refreshToken());
        String id = sessions.findIdByRefreshHash(hash).orElseThrow(TokenExchangeException::notFound);
        idempotency.run("logout:" + id, key, List.of(hash, request.deviceId()), Boolean.class, () -> {
            var stored = sessions.lockById(id).orElseThrow(TokenExchangeException::notFound);
            Instant now = Instant.now();
            if (!stored.revoked() && stored.session().expiresAt().isAfter(now)) {
                sessions.revoke(id, now);
                devices.deleteOwnedRegistration(request.deviceId(), stored.session().userId());
            }
            return true;
        });
    }
}
