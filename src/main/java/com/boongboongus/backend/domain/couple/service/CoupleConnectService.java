package com.boongboongus.backend.domain.couple.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse.Couple;
import com.boongboongus.backend.domain.couple.repository.CoupleInviteRepository;
import com.boongboongus.backend.domain.couple.repository.CoupleRepository;
import com.boongboongus.backend.domain.couple.repository.CoupleRepository.Candidates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CoupleConnectService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private final CoupleInviteRepository invites;
    private final CoupleRepository couples;
    private final CoupleService views;
    private final IdempotentWrite idempotency;

    CoupleConnectService(CoupleInviteRepository invites, CoupleRepository couples, CoupleService views,
            IdempotentWrite idempotency) {
        this.invites = invites;
        this.couples = couples;
        this.views = views;
        this.idempotency = idempotency;
    }

    public Couple connect(String userId, String key, String rawCode) {
        return accept(userId, key, rawCode, false);
    }

    public Couple restore(String userId, String key, String rawCode) {
        return accept(userId, key, rawCode, true);
    }

    private Couple accept(String userId, String key, String rawCode, boolean restore) {
        String code = rawCode.strip().toLowerCase(Locale.ROOT);
        String operation = restore ? "restore-couple" : "connect-couple";
        return idempotency.run(userId, key, List.of(operation, code), Couple.class,
                () -> acceptInvite(userId, code, restore));
    }

    private Couple acceptInvite(String userId, String code, boolean restore) {
        String owner = invites.findOwnerByCode(code).orElseThrow(CoupleConnectService::invalidInvite);
        if (owner.equals(userId)) {
            throw new Rejected(HttpStatus.UNPROCESSABLE_CONTENT, "SELF_INVITE", "본인의 초대 코드는 사용할 수 없습니다.");
        }

        // All invite mutations lock member rows first, in the same order.
        for (String member : List.of(owner, userId).stream().sorted().toList()) {
            if (!invites.lockOwner(member)) {
                throw new Rejected(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "회원을 찾을 수 없습니다.");
            }
        }
        var invite = invites.findByCode(code).orElseThrow(CoupleConnectService::invalidInvite);
        if (invite.consumed()) {
            throw new Rejected(HttpStatus.CONFLICT, "INVITE_CONSUMED", "이미 사용한 초대 코드입니다.");
        }
        if (!invite.active() || !invite.expiresAt().isAfter(Instant.now())) {
            throw invalidInvite();
        }
        if (couples.hasActiveConnection(owner, userId)) {
            throw new Rejected(HttpStatus.CONFLICT, "ALREADY_COUPLED", "이미 연결된 커플이 있습니다.");
        }
        var history = couples.findLatestDisconnectedPair(owner, userId);
        if (restore) {
            String priorId = history.orElseThrow(() -> new Rejected(
                    HttpStatus.NOT_FOUND, "COUPLE_HISTORY_NOT_FOUND", "복구할 커플 연결이 없습니다."));
            couples.restore(priorId);
        } else {
            if (history.isPresent()) {
                throw new Rejected(HttpStatus.CONFLICT, "COUPLE_RESTORE_REQUIRED", "이전 커플 연결을 복구해 주세요.");
            }
            create(owner, userId);
        }
        invites.consume(invite.id(), Instant.now());
        invites.deactivateForOwner(userId);
        return views.getCurrent(userId).couple();
    }

    private void create(String owner, String userId) {
        Candidates issuer = couples.getCandidates(owner);
        Candidates recipient = couples.getCandidates(userId);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate start = firstPresent(issuer.startDate(), recipient.startDate(), today);
        if (start.isAfter(today)) {
            throw new Rejected(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_START_DATE", "시작일은 미래일 수 없습니다.");
        }
        String mascot = firstPresent(issuer.mascotName(), recipient.mascotName(), "붕붕이");
        couples.create("cpl_" + UUID.randomUUID(), owner, userId, start, mascot);
    }

    private static <T> T firstPresent(T first, T second, T fallback) {
        return first != null ? first : (second != null ? second : fallback);
    }

    private static Rejected invalidInvite() {
        return new Rejected(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_INVITE", "유효한 초대 코드를 입력해 주세요.");
    }

    public static final class Rejected extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        Rejected(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
