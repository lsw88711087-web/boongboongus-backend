package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Route;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class DrivePreparationStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    DrivePreparationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean insert(DriveRow drive) {
        return jdbc.sql("""
                INSERT INTO drives (
                    id, couple_id, title, status, active_slot, driver_user_id, passenger_user_id,
                    recorder_user_id, route_id, origin_snapshot, destination_snapshot, control_version
                ) VALUES (
                    :id, :coupleId, :title, 'PREPARING', 1, :driverId, :passengerId,
                    :recorderId, :routeId, :origin, :destination, 1
                )
                ON CONFLICT DO NOTHING
                """)
                .param("id", drive.id())
                .param("coupleId", drive.coupleId())
                .param("title", drive.title())
                .param("driverId", drive.driverUserId())
                .param("passengerId", drive.passengerUserId())
                .param("recorderId", drive.recorderUserId())
                .param("routeId", drive.routeId())
                .param("origin", drive.origin() == null ? null : json.writeValueAsString(drive.origin()))
                .param("destination", drive.destination() == null ? null : json.writeValueAsString(drive.destination()))
                .update() == 1;
    }

    Optional<DriveRow> findActive(String coupleId) {
        return jdbc.sql("SELECT * FROM drives WHERE couple_id = :coupleId AND active_slot = 1")
                .param("coupleId", coupleId)
                .query(this::readDrive)
                .optional();
    }

    Optional<DriveRow> lockDrive(String coupleId, String driveId) {
        return findDrive(coupleId, driveId, true);
    }

    Optional<DriveRow> findDrive(String coupleId, String driveId) {
        return findDrive(coupleId, driveId, false);
    }

    private Optional<DriveRow> findDrive(String coupleId, String driveId, boolean forUpdate) {
        String sql = "SELECT * FROM drives WHERE couple_id = :coupleId AND id = :id";
        return jdbc.sql(forUpdate ? sql + " FOR UPDATE" : sql)
                .param("coupleId", coupleId)
                .param("id", driveId)
                .query(this::readDrive)
                .optional();
    }

    void cancel(DriveRow drive) {
        jdbc.sql("""
                UPDATE drives SET status = 'CANCELLED', active_slot = NULL, control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .update();
    }

    void update(DriveRow drive, String title, String routeId, Route route) {
        jdbc.sql("""
                UPDATE drives SET title = :title, route_id = :routeId, origin_snapshot = :origin,
                    destination_snapshot = :destination, control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("title", title)
                .param("routeId", route == null ? drive.routeId() : routeId)
                .param("origin", snapshot(route == null ? drive.origin() : route.origin()))
                .param("destination", snapshot(route == null ? drive.destination() : route.destination()))
                .update();
    }

    void start(DriveRow drive, DriveControlRequest.InitialCoordinate coordinate, Instant now) {
        jdbc.sql("""
                UPDATE drives SET status = 'ACTIVE', started_at = :now, state_changed_at = :now,
                    initial_origin_snapshot = origin_snapshot, initial_coordinate = :coordinate,
                    control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("coordinate", snapshot(coordinate))
                .update();
    }

    void pause(DriveRow drive, Instant now) {
        updateClock(drive, "PAUSED", now);
        jdbc.sql("""
                INSERT INTO drive_pause_periods (drive_id, pause_version, paused_at) VALUES (:id, :version, :now)
                """)
                .param("id", drive.id())
                .param("version", drive.controlVersion() + 1)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    void resume(DriveRow drive, Instant now) {
        updateClock(drive, "ACTIVE", now);
        int resumed = jdbc.sql("""
                UPDATE drive_pause_periods SET resumed_at = :now WHERE drive_id = :id AND resumed_at IS NULL
                """)
                .param("id", drive.id())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
        if (resumed != 1) {
            throw new IllegalStateException("Expected exactly one open pause period");
        }
    }

    private void updateClock(DriveRow drive, String status, Instant now) {
        jdbc.sql("""
                UPDATE drives SET status = :status, state_changed_at = :now,
                    active_millis = :activeMillis, paused_millis = :pausedMillis,
                    control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("status", status)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("activeMillis", drive.activeMillisAt(now))
                .param("pausedMillis", drive.pausedMillisAt(now))
                .update();
    }

    void end(DriveRow drive, DriveGpsRequest.EndReason reason, Instant now) {
        jdbc.sql("""
                UPDATE drives SET status = 'ENDED', active_slot = NULL, ended_at = :now, state_changed_at = :now,
                    end_reason = :reason, active_millis = :activeMillis, paused_millis = :pausedMillis,
                    control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("activeMillis", drive.activeMillisAt(now))
                .param("pausedMillis", drive.pausedMillisAt(now))
                .update();
    }

    List<DriveGps.Pause> pauses(String driveId) {
        return jdbc.sql("""
                SELECT paused_at, resumed_at FROM drive_pause_periods WHERE drive_id = :id ORDER BY pause_version
                """)
                .param("id", driveId)
                .query((rs, row) -> new DriveGps.Pause(instant(rs, "paused_at"), instant(rs, "resumed_at")))
                .list();
    }

    Optional<Coordinate> latestCoordinate(String driveId) {
        return jdbc.sql("""
                SELECT latitude, longitude FROM drive_gps_samples
                WHERE drive_id = :id AND latitude IS NOT NULL AND longitude IS NOT NULL
                ORDER BY recorded_at DESC, sequence DESC LIMIT 1
                """).param("id", driveId)
                .query((row, index) -> new Coordinate(row.getDouble("latitude"), row.getDouble("longitude"))).optional();
    }

    NavigableMap<Integer, DriveGps.Sample> gpsHistory(String driveId) {
        var samples = new TreeMap<Integer, DriveGps.Sample>();
        jdbc.sql("SELECT sequence, recorded_at, latitude, longitude FROM drive_gps_samples WHERE drive_id = :id")
                .param("id", driveId)
                .query((rs, row) -> {
                    Double latitude = rs.getObject("latitude", Double.class);
                    Coordinate coordinate = latitude == null ? null : new Coordinate(latitude, rs.getDouble("longitude"));
                    return new DriveGps.Sample(rs.getInt("sequence"), instant(rs, "recorded_at"), coordinate);
                })
                .list()
                .forEach(sample -> samples.put(sample.sequence(), sample));
        return samples;
    }

    void saveGpsSample(String driveId, DriveGps.Sample sample, boolean replace) {
        String sql = replace ? """
                UPDATE drive_gps_samples SET recorded_at = :at, latitude = :latitude, longitude = :longitude
                WHERE drive_id = :id AND sequence = :sequence
                """ : """
                INSERT INTO drive_gps_samples (drive_id, sequence, recorded_at, latitude, longitude)
                VALUES (:id, :sequence, :at, :latitude, :longitude)
                """;
        Coordinate coordinate = sample.coordinate();
        jdbc.sql(sql)
                .param("id", driveId)
                .param("sequence", sample.sequence())
                .param("at", sample.recordedAt().atOffset(ZoneOffset.UTC))
                .param("latitude", coordinate == null ? null : coordinate.latitude())
                .param("longitude", coordinate == null ? null : coordinate.longitude())
                .update();
    }

    void updateGpsMetrics(String driveId, DriveGps.Summary summary) {
        jdbc.sql("""
                UPDATE drives SET distance_meters = :distance, last_gps_at = :lastGps, gps_quality = :quality
                WHERE id = :id
                """)
                .param("id", driveId)
                .param("distance", summary.distanceMeters())
                .param("lastGps", summary.lastGpsAt() == null ? null : summary.lastGpsAt().atOffset(ZoneOffset.UTC))
                .param("quality", summary.gpsQuality())
                .update();
    }

    boolean reserve(RequestKey key, String fingerprint, Instant expiresAt) {
        return request("""
                INSERT INTO drive_idempotency (couple_id, user_id, idempotency_key, fingerprint, expires_at)
                VALUES (:coupleId, :userId, :key, :fingerprint, :expiresAt)
                ON CONFLICT DO NOTHING
                """, key)
                .param("fingerprint", fingerprint)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    StoredRequest lockRequest(RequestKey key) {
        return request("""
                SELECT fingerprint, expires_at, response_snapshot FROM drive_idempotency
                WHERE couple_id = :coupleId AND user_id = :userId AND idempotency_key = :key
                FOR UPDATE
                """, key)
                .query((rs, row) -> new StoredRequest(
                        rs.getString("fingerprint"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getString("response_snapshot")))
                .single();
    }

    void saveResult(RequestKey key, String fingerprint, Instant expiresAt, Object result) {
        request("""
                UPDATE drive_idempotency
                SET fingerprint = :fingerprint, expires_at = :expiresAt, response_snapshot = :snapshot
                WHERE couple_id = :coupleId AND user_id = :userId AND idempotency_key = :key
                """, key)
                .param("fingerprint", fingerprint)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .param("snapshot", json.writeValueAsString(result))
                .update();
    }

    private JdbcClient.StatementSpec request(String sql, RequestKey key) {
        return jdbc.sql(sql)
                .param("coupleId", key.coupleId())
                .param("userId", key.userId())
                .param("key", key.key());
    }

    private DriveRow readDrive(ResultSet rs, int row) throws SQLException {
        return new DriveRow(
                rs.getString("id"), rs.getString("couple_id"), rs.getString("title"),
                rs.getString("status"), rs.getString("driver_user_id"), rs.getString("passenger_user_id"),
                rs.getString("recorder_user_id"), rs.getString("route_id"), readPlace(rs.getString("origin_snapshot")),
                readPlace(rs.getString("destination_snapshot")), rs.getInt("control_version"),
                instant(rs, "started_at"), instant(rs, "state_changed_at"),
                rs.getLong("active_millis"), rs.getLong("paused_millis"), instant(rs, "ended_at"),
                rs.getString("end_reason"), rs.getDouble("distance_meters"), instant(rs, "last_gps_at"),
                rs.getString("gps_quality"));
    }

    private String snapshot(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Place readPlace(String snapshot) {
        return snapshot == null ? null : json.readValue(snapshot, Place.class);
    }

    record DriveRow(String id, String coupleId, String title, String status, String driverUserId,
            String passengerUserId, String recorderUserId, String routeId, Place origin, Place destination,
            int controlVersion, Instant startedAt, Instant stateChangedAt, long activeMillis, long pausedMillis,
            Instant endedAt, String endReason, double distanceMeters, Instant lastGpsAt, String gpsQuality) {

        DriveRow(String id, String coupleId, String title, String status, String driverUserId,
                String passengerUserId, String recorderUserId, String routeId, Place origin, Place destination,
                int controlVersion) {
            this(id, coupleId, title, status, driverUserId, passengerUserId, recorderUserId, routeId, origin,
                    destination, controlVersion, null, null, 0, 0, null, null, 0, null, "MISSING");
        }

        DriveView view() {
            return view(Instant.now());
        }

        DriveView view(Instant now) {
            Instant pausedAt = "PAUSED".equals(status) ? stateChangedAt : null;
            Instant editableUntil = endedAt == null ? null : endedAt.plus(Duration.ofHours(24));
            return new DriveView(id, coupleId, title, status, driverUserId, passengerUserId, recorderUserId,
                    origin, destination, routeId, startedAt, pausedAt, endedAt, endReason, controlVersion,
                    new DriveView.Metrics(distanceMeters, Math.toIntExact(activeMillisAt(now) / 1000),
                            Math.toIntExact(pausedMillisAt(now) / 1000), null, lastGpsAt, gpsQuality),
                    editableUntil != null && now.isBefore(editableUntil), null, editableUntil, null);
        }

        long activeMillisAt(Instant now) {
            return activeMillis + elapsedWhile("ACTIVE", now);
        }

        long pausedMillisAt(Instant now) {
            return pausedMillis + elapsedWhile("PAUSED", now);
        }

        private long elapsedWhile(String phase, Instant now) {
            return phase.equals(status) && stateChangedAt != null
                    ? Math.max(0, Duration.between(stateChangedAt, now).toMillis()) : 0;
        }
    }

    record RequestKey(String coupleId, String userId, String key) {
    }

    record StoredRequest(String fingerprint, Instant expiresAt, String responseSnapshot) {
    }
}
