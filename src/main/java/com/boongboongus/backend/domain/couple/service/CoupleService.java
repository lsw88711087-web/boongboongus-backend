package com.boongboongus.backend.domain.couple.service;

import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse;
import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse.Status;
import com.boongboongus.backend.domain.couple.repository.CoupleRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Fallback;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
@Fallback
public class CoupleService implements CurrentCoupleLookup {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private final CoupleRepository couples;

    CoupleService(CoupleRepository couples) {
        this.couples = couples;
    }

    @Override
    public Optional<CurrentCoupleLookup.Couple> findCurrent(String userId) {
        return couples.findLatest(userId).filter(CoupleRepository.Connection::connected)
                .map(connection -> new CurrentCoupleLookup.Couple(
                        connection.id(), connection.first().id(), connection.second().id()));
    }

    @Override
    public boolean lockCurrent(String userId, String coupleId) {
        return couples.lockMembership(coupleId, userId) && CurrentCoupleLookup.super.lockCurrent(userId, coupleId);
    }

    @Transactional
    public void disconnect(String userId, String coupleId, DrivePreparationService drives) {
        if (!couples.lockMembership(coupleId, userId)) {
            throw contextChanged();
        }
        var latest = couples.findLatest(userId).filter(connection -> connection.id().equals(coupleId))
                .orElseThrow(CoupleService::contextChanged);
        if (!latest.connected()) {
            return;
        }
        if (drives.findActiveDriveId(coupleId).isPresent()) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.CONFLICT, "ACTIVE_DRIVE_EXISTS", "진행 중인 주행을 먼저 종료하거나 취소해 주세요.");
        }
        couples.disconnect(coupleId);
    }

    @Transactional
    public CurrentCoupleResponse.Couple updateStartDate(String userId, String coupleId, LocalDate startDate,
            int expectedVersion) {
        lockForUpdate(userId, coupleId);
        if (startDate.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_START_DATE", "시작일은 미래일 수 없습니다.");
        }
        requireUpdated(couples.updateStartDate(coupleId, startDate, expectedVersion));
        return getCurrent(userId).couple();
    }

    @Transactional
    public CurrentCoupleResponse.Couple updateMascotName(String userId, String coupleId, String name,
            int expectedVersion) {
        lockForUpdate(userId, coupleId);
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 20) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "붕붕이 이름은 1~20자여야 합니다.");
        }
        requireUpdated(couples.updateMascotName(coupleId, trimmed, expectedVersion));
        return getCurrent(userId).couple();
    }

    private void lockForUpdate(String userId, String coupleId) {
        if (findCurrent(userId).isEmpty()) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "커플 연결이 필요합니다.");
        }
        if (!lockCurrent(userId, coupleId)) {
            throw contextChanged();
        }
    }

    private static void requireUpdated(boolean updated) {
        if (!updated) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.CONFLICT, "VERSION_CONFLICT", "최신 커플 정보를 조회한 뒤 다시 시도해 주세요.");
        }
    }

    private static CoupleConnectService.Rejected contextChanged() {
        return new CoupleConnectService.Rejected(
                HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 연결이 변경되었습니다.");
    }

    public CurrentCoupleResponse getCurrent(String userId) {
        return couples.findLatest(userId).map(connection -> {
            if (!connection.connected()) {
                return new CurrentCoupleResponse(null, Status.DISCONNECTED);
            }
            long days = ChronoUnit.DAYS.between(connection.startDate(), LocalDate.now(BUSINESS_ZONE)) + 1;
            // Card storage is introduced by the records feature.
            return new CurrentCoupleResponse(new CurrentCoupleResponse.Couple(connection.id(), Status.CONNECTED,
                    List.of(connection.first(), connection.second()), connection.startDate(), days, 0, 0,
                    connection.version(), connection.mascotName()), Status.CONNECTED);
        }).orElseGet(() -> new CurrentCoupleResponse(null, Status.UNLINKED));
    }
}
