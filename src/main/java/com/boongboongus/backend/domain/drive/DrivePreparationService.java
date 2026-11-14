package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup.Couple;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.DriveRow;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.RequestKey;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.StoredRequest;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Route;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DrivePreparationService {
    private static final Duration REPLAY_WINDOW = Duration.ofHours(24);
    private static final Duration LATE_GPS_WINDOW = Duration.ofMinutes(15);
    private static final String DEFAULT_TITLE = "우리의 드라이브";

    private final DrivePreparationStore store;
    private final ObjectProvider<CurrentCoupleLookup> couples;
    private final ObjectProvider<DriveRouteLookup> routes;
    private final ObjectProvider<DriveLocationConsentLookup> locationConsents;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final RestAreaService restAreas;
    private final StampService stamps;

    DrivePreparationService(DrivePreparationStore store, ObjectProvider<CurrentCoupleLookup> couples,
            ObjectProvider<DriveRouteLookup> routes, ObjectProvider<DriveLocationConsentLookup> locationConsents,
            TransactionTemplate transaction, JsonMapper json, RestAreaService restAreas, StampService stamps) {
        this.store = store;
        this.couples = couples;
        this.routes = routes;
        this.locationConsents = locationConsents;
        this.transaction = transaction;
        this.json = json;
        this.restAreas = restAreas;
        this.stamps = stamps;
    }

    DriveView prepare(Principal principal, String coupleId, String key, DrivePreparationRequest request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("prepare", request), () -> {
            CurrentCoupleLookup lookup = couples.getIfAvailable();
            if (lookup == null || !lookup.lockCurrent(actor.userId(), coupleId)) {
                throw error(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 연결이 변경되었습니다.");
            }
            validateRoles(actor.couple(), request);
            Route route = request.routeId() == null ? null : loadRoute(actor, request.routeId());
            DriveRow drive = new DriveRow(
                    "drv_" + UUID.randomUUID(), coupleId, normalizeTitle(request.title()),
                    "PREPARING", request.driverUserId(), request.passengerUserId(), request.recorderUserId(),
                    request.routeId(), route == null ? null : route.origin(),
                    route == null ? null : route.destination(), 1);
            if (!store.insert(drive)) {
                throw error(HttpStatus.CONFLICT, "ACTIVE_DRIVE_EXISTS", "커플에 이미 진행 가능한 주행이 있습니다.");
            }
            return drive.view();
        });
    }

    DriveView active(Principal principal) {
        Actor actor = currentActor(principal);
        return store.findActive(actor.couple().id()).map(DriveRow::view).orElse(null);
    }

    public Optional<String> findActiveDriveId(String coupleId) {
        return store.findActive(coupleId).map(DriveRow::id);
    }

    DriveView cancel(Principal principal, String coupleId, String key, String driveId,
            DrivePreparationRequest.Version request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("cancel", driveId, request), () -> {
            DriveRow drive = lockVersion(coupleId, driveId, request.expectedVersion());
            requireState(drive, "PREPARING");
            store.cancel(drive);
            return findDrive(coupleId, driveId).view();
        });
    }

    DriveView get(Principal principal, String driveId) {
        Actor actor = currentActor(principal);
        return findDrive(actor.couple().id(), driveId).view();
    }

    DriveView update(Principal principal, String coupleId, String driveId, DriveControlRequest.Patch request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        if (request.title() == null && request.routeId() == null) {
            throw error(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "제목이나 경로를 입력해 주세요.");
        }
        return transaction.execute(status -> {
            DriveRow drive = lockVersion(coupleId, driveId, request.expectedVersion());
            if (!List.of("PREPARING", "ACTIVE", "PAUSED").contains(drive.status())) {
                throw stateConflict();
            }
            Route route = request.routeId() == null ? null : loadRoute(actor, request.routeId());
            String title = request.title() == null ? drive.title() : normalizeTitle(request.title());
            store.update(drive, title, request.routeId(), route);
            return findDrive(coupleId, driveId).view();
        });
    }

    DriveView start(Principal principal, String coupleId, String key, String driveId, DriveControlRequest.Start request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("start", driveId, request), () -> {
            DriveRow drive = lockVersion(coupleId, driveId, request.expectedVersion());
            requireState(drive, "PREPARING");
            if (!drive.recorderUserId().equals(actor.userId())) {
                throw error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "기록 사용자만 주행을 시작할 수 있습니다.");
            }
            if (drive.routeId() == null || drive.destination() == null) {
                throw error(HttpStatus.UNPROCESSABLE_CONTENT, "DESTINATION_REQUIRED", "목적지와 경로를 선택해 주세요.");
            }
            if (request.initialCoordinate() == null) {
                throw error(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_REQUIRED", "시작 위치를 입력해 주세요.");
            }
            requireLocationConsent(actor.userId());
            Instant now = controlTime(drive);
            store.start(drive, request.initialCoordinate(), now);
            return findDrive(coupleId, driveId).view(now);
        });
    }

    DriveView pause(Principal principal, String coupleId, String key, String driveId,
            DrivePreparationRequest.Version request) {
        return changePhase(principal, coupleId, key, driveId, request, "pause", "ACTIVE", store::pause);
    }

    DriveView resume(Principal principal, String coupleId, String key, String driveId,
            DrivePreparationRequest.Version request) {
        return changePhase(principal, coupleId, key, driveId, request, "resume", "PAUSED", store::resume);
    }

    DriveGps.Receipt uploadGpsBatch(Principal principal, String coupleId, String key, String driveId,
            DriveGpsRequest.Batch request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("gps-batch", driveId, request), DriveGps.Receipt.class, () -> {
            DriveRow drive = store.lockDrive(coupleId, driveId).orElseThrow(DrivePreparationService::driveNotFound);
            if (!drive.recorderUserId().equals(actor.userId())) {
                throw error(HttpStatus.FORBIDDEN, "GPS_RECORDER_ONLY", "기록 사용자만 GPS 좌표를 보낼 수 있습니다.");
            }
            if (!List.of("ACTIVE", "PAUSED", "ENDED").contains(drive.status())) {
                throw stateConflict();
            }
            Instant now = controlTime(drive);
            if (drive.endedAt() != null && now.isAfter(drive.endedAt().plus(LATE_GPS_WINDOW))) {
                throw error(HttpStatus.GONE, "GPS_UPLOAD_WINDOW_CLOSED", "GPS 업로드 가능 시간이 지났습니다.");
            }
            return recordGpsBatch(drive, request, now);
        });
    }

    private DriveGps.Receipt recordGpsBatch(DriveRow drive, DriveGpsRequest.Batch request, Instant now) {
        var pauses = store.pauses(drive.id());
        // ponytail: scan this drive's samples per batch. Incrementally update adjacent segments if load warrants it.
        var history = store.gpsHistory(drive.id());
        List<Integer> accepted = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        List<DriveGps.Rejection> rejected = new ArrayList<>();
        for (DriveGpsRequest.Sample input : request.samples()) {
            DriveGps.Sample existing = history.get(input.sequence());
            if (existing != null && existing.coordinate() != null) {
                duplicates.add(input.sequence());
                continue;
            }
            DriveGps.Sample sample = DriveGps.from(input);
            String rejection = DriveGps.rejection(input, sample, history, drive, pauses, now);
            if (rejection == null) {
                accepted.add(input.sequence());
            } else {
                rejected.add(new DriveGps.Rejection(input.sequence(), rejection));
                if (!"ACCURACY_TOO_LOW".equals(rejection)) {
                    continue;
                }
                // Keep a time marker so low accuracy breaks arrival dwell, without retaining its coordinates.
                sample = new DriveGps.Sample(sample.sequence(), sample.recordedAt(), null);
            }
            store.saveGpsSample(drive.id(), sample, existing != null);
            history.put(sample.sequence(), sample);
        }
        var destination = drive.destination() == null ? null : drive.destination().coordinate();
        DriveGps.Summary summary = DriveGps.summarize(history, destination, pauses);
        store.updateGpsMetrics(drive.id(), summary);
        List<RestAreaService.Alert> alerts = List.of();
        if (!accepted.isEmpty()) {
            stamps.resolvePending(drive, history.values(), now);
            var latest = history.descendingMap().values().stream()
                    .filter(sample -> sample.coordinate() != null).findFirst();
            if (latest.isPresent() && accepted.contains(latest.get().sequence())) {
                alerts = restAreas.approachAlerts(drive, latest.get().coordinate(), now);
            }
        }
        return new DriveGps.Receipt(accepted, duplicates, rejected, summary.highestContiguousSequence(),
                drive.endedAt() == null && summary.arrivalCandidate(),
                findDrive(drive.coupleId(), drive.id()).view(now), alerts);
    }

    DriveView endDrive(Principal principal, String coupleId, String key, String driveId, DriveGpsRequest.End request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("end", driveId, request), () -> {
            DriveRow drive = lockVersion(coupleId, driveId, request.expectedVersion());
            if (!"ACTIVE".equals(drive.status()) && !"PAUSED".equals(drive.status())) {
                throw stateConflict();
            }
            if (request.reason() == DriveGpsRequest.EndReason.ARRIVED) {
                verifyArrival(drive, request.lastRecordedSequence());
            }
            Instant now = controlTime(drive);
            store.end(drive, request.reason(), now);
            return findDrive(coupleId, driveId).view(now);
        });
    }

    private void verifyArrival(DriveRow drive, int lastRecordedSequence) {
        var history = store.gpsHistory(drive.id());
        var destination = drive.destination() == null ? null : drive.destination().coordinate();
        if (history.isEmpty() || history.lastKey() != lastRecordedSequence
                || !DriveGps.summarize(history, destination, store.pauses(drive.id())).arrivalCandidate()) {
            throw error(HttpStatus.UNPROCESSABLE_CONTENT, "ARRIVAL_NOT_VERIFIED", "GPS로 목적지 도착을 확인하지 못했습니다.");
        }
    }

    private DriveView changePhase(Principal principal, String coupleId, String key, String driveId,
            DrivePreparationRequest.Version request, String operation, String requiredState,
            BiConsumer<DriveRow, Instant> change) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of(operation, driveId, request), () -> {
            DriveRow drive = lockVersion(coupleId, driveId, request.expectedVersion());
            requireState(drive, requiredState);
            Instant now = controlTime(drive);
            change.accept(drive, now);
            return findDrive(coupleId, driveId).view(now);
        });
    }

    private DriveRow findDrive(String coupleId, String driveId) {
        return store.findDrive(coupleId, driveId).orElseThrow(DrivePreparationService::driveNotFound);
    }

    private DriveRow lockVersion(String coupleId, String driveId, int expectedVersion) {
        DriveRow drive = store.lockDrive(coupleId, driveId).orElseThrow(DrivePreparationService::driveNotFound);
        if (drive.controlVersion() != expectedVersion) {
            throw error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "주행 정보를 다시 조회해 주세요.");
        }
        return drive;
    }

    private static void requireState(DriveRow drive, String state) {
        if (!state.equals(drive.status())) {
            throw stateConflict();
        }
    }

    private static DriveApiException stateConflict() {
        return error(HttpStatus.CONFLICT, "DRIVE_STATE_CONFLICT", "현재 주행 상태에서는 요청을 처리할 수 없습니다.");
    }

    private static DriveApiException driveNotFound() {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "주행을 찾을 수 없습니다.");
    }

    private static String normalizeTitle(String value) {
        return value == null || value.isBlank() ? DEFAULT_TITLE : value.strip();
    }

    private static Instant controlTime(DriveRow drive) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return drive.stateChangedAt() != null && now.isBefore(drive.stateChangedAt()) ? drive.stateChangedAt() : now;
    }

    private void requireLocationConsent(String userId) {
        DriveLocationConsentLookup lookup = locationConsents.getIfAvailable();
        if (lookup == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "위치 정보 동의를 확인할 수 없습니다.");
        }
        if (!lookup.hasLocationConsent(userId)) {
            throw error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "주행을 시작하려면 위치 정보 동의가 필요합니다.");
        }
    }

    private DriveView executeOnce(Actor actor, String key, List<?> intent, Supplier<DriveView> action) {
        return executeOnce(actor, key, intent, DriveView.class, action);
    }

    private <T> T executeOnce(Actor actor, String key, List<?> intent, Class<T> resultType, Supplier<T> action) {
        RequestKey requestKey = new RequestKey(actor.couple().id(), actor.userId(), key);
        String fingerprint = fingerprint(intent);
        return Objects.requireNonNull(transaction.execute(status -> {
            Instant now = Instant.now();
            // A concurrent retry waits on the unique key until the first transaction commits.
            if (!store.reserve(requestKey, fingerprint, now.plus(REPLAY_WINDOW))) {
                StoredRequest previous = store.lockRequest(requestKey);
                now = Instant.now();
                if (previous.expiresAt().isAfter(now)) {
                    if (!fingerprint.equals(previous.fingerprint())) {
                        throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                                "같은 멱등 키에 다른 요청을 사용할 수 없습니다.");
                    }
                    return json.readValue(previous.responseSnapshot(), resultType);
                }
            }
            T result = action.get();
            store.saveResult(requestKey, fingerprint, Instant.now().plus(REPLAY_WINDOW), result);
            return result;
        }));
    }

    private String fingerprint(List<?> intent) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(intent));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String userId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        return principal.getName();
    }

    String optionalCoupleId(Principal principal) {
        String user = userId(principal);
        CurrentCoupleLookup lookup = couples.getIfAvailable();
        return lookup == null ? null : lookup.findCurrent(user).filter(couple -> couple.contains(user))
                .map(Couple::id).orElse(null);
    }

    Actor currentActor(Principal principal) {
        String userId = userId(principal);
        CurrentCoupleLookup lookup = couples.getIfAvailable();
        if (lookup == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "커플 정보를 확인할 수 없습니다.");
        }
        Couple couple = lookup.findCurrent(userId)
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
        if (!couple.contains(userId)) {
            throw error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "이 커플에 접근할 수 없습니다.");
        }
        return new Actor(userId, couple);
    }

    void requireMatchingCouple(Actor actor, String requestedCoupleId) {
        if (!actor.couple().id().equals(requestedCoupleId)) {
            throw error(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
        }
    }

    private void validateRoles(Couple couple, DrivePreparationRequest request) {
        String driver = request.driverUserId();
        String passenger = request.passengerUserId();
        String recorder = request.recorderUserId();
        if (driver.equals(passenger) || !couple.contains(driver) || !couple.contains(passenger)
                || (!recorder.equals(driver) && !recorder.equals(passenger))) {
            throw error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRIVE_MEMBERS",
                    "운전자, 동승자, 기록 기기의 사용자 지정을 확인해 주세요.");
        }
    }

    private Route loadRoute(Actor actor, String routeId) {
        DriveRouteLookup lookup = routes.getIfAvailable();
        if (lookup == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "경로 정보를 확인할 수 없습니다.");
        }
        return lookup.findAccessible(actor.userId(), actor.couple().id(), routeId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "경로를 찾을 수 없습니다."));
    }

    private static DriveApiException error(HttpStatus status, String code, String message) {
        return new DriveApiException(status, code, message);
    }

    record Actor(String userId, Couple couple) {
    }
}
