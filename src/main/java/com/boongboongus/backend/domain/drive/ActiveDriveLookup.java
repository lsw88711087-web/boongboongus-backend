package com.boongboongus.backend.domain.drive;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ActiveDriveLookup {
    private final JdbcClient jdbc;

    ActiveDriveLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsForMember(String userId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM drives WHERE active_slot = 1
                    AND (driver_user_id = :userId OR passenger_user_id = :userId)
                )
                """).param("userId", userId).query(Boolean.class).single();
    }
}
