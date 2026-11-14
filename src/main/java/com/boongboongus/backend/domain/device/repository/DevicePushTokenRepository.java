package com.boongboongus.backend.domain.device.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DevicePushTokenRepository {
    private final JdbcClient jdbc;

    DevicePushTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean register(String deviceId, String userId, String token, String platform, boolean enabled) {
        return jdbc.sql("""
                MERGE INTO device_push_tokens d
                USING (SELECT id FROM auth_users WHERE id = :userId) account
                ON d.device_id = :deviceId
                WHEN MATCHED THEN UPDATE SET user_id = account.id, push_token = :token,
                    platform = :platform, enabled = :enabled
                WHEN NOT MATCHED THEN INSERT (device_id, user_id, push_token, platform, enabled)
                    VALUES (:deviceId, account.id, :token, :platform, :enabled)
                """).param("deviceId", deviceId).param("userId", userId).param("token", token)
                .param("platform", platform).param("enabled", enabled).update() == 1;
    }

    public void deleteOwnedRegistration(String deviceId, String userId) {
        jdbc.sql("DELETE FROM device_push_tokens WHERE device_id = :deviceId AND user_id = :userId")
                .param("deviceId", deviceId).param("userId", userId).update();
    }
}
