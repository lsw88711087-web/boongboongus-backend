package com.boongboongus.backend.domain.device.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DevicePushTokenRepository {
    private final JdbcClient jdbc;

    DevicePushTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteOwnedRegistration(String deviceId, String userId) {
        jdbc.sql("DELETE FROM device_push_tokens WHERE device_id = :deviceId AND user_id = :userId")
                .param("deviceId", deviceId).param("userId", userId).update();
    }
}
