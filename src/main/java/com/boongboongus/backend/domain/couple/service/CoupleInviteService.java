package com.boongboongus.backend.domain.couple.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.couple.dto.CoupleInviteResponse;
import com.boongboongus.backend.domain.couple.dto.CoupleInviteResponse.Status;
import com.boongboongus.backend.domain.couple.repository.CoupleInviteRepository;
import com.boongboongus.backend.domain.couple.repository.CoupleRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CoupleInviteService {
    private static final Duration INVITE_TTL = Duration.ofHours(24);
    private final CoupleInviteRepository invites;
    private final CoupleRepository couples;
    private final IdempotentWrite idempotency;
    private final SecureRandom random = new SecureRandom();

    CoupleInviteService(CoupleInviteRepository invites, CoupleRepository couples, IdempotentWrite idempotency) {
        this.invites = invites;
        this.couples = couples;
        this.idempotency = idempotency;
    }

    @Transactional
    public void revoke(String userId, String inviteId) {
        if (!invites.lockOwner(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        boolean used = invites.findOwnedConsumption(inviteId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (used) {
            throw new CoupleConnectService.Rejected(
                    HttpStatus.CONFLICT, "INVITE_ALREADY_USED", "이미 커플 연결에 사용한 초대 코드입니다.");
        }
        invites.revoke(inviteId);
    }

    public java.util.Optional<CoupleInviteResponse> findCurrent(String userId) {
        return invites.findActive(userId, Instant.now());
    }

    public CoupleInviteResponse createOrGet(String userId, String key) {
        return idempotency.run(userId, key, List.of("create-couple-invite"), CoupleInviteResponse.class, () -> {
            if (!invites.lockOwner(userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (couples.findLatest(userId).filter(CoupleRepository.Connection::connected).isPresent()) {
                throw new AlreadyCoupled();
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            return invites.findActive(userId, now).orElseGet(() -> issue(userId, now));
        });
    }

    private CoupleInviteResponse issue(String userId, Instant now) {
        invites.clearExpiredSlot(userId, now);
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        var invite = new CoupleInviteResponse("inv_" + UUID.randomUUID(), HexFormat.of().formatHex(bytes),
                now.plus(INVITE_TTL), Status.ACTIVE);
        invites.save(userId, invite);
        return invite;
    }

    public static final class AlreadyCoupled extends RuntimeException {}
}
