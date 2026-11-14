package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.boongboongus.backend.domain.auth.dto.AuthorizationResponse;
import com.boongboongus.backend.domain.auth.dto.ReauthRequest;
import com.boongboongus.backend.domain.auth.dto.ReauthResponse;
import com.boongboongus.backend.domain.auth.dto.TokenRequest;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.auth.repository.AuthSessionRepository;
import com.boongboongus.backend.domain.auth.repository.ReauthProofRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthReauthService {
    private final AuthAuthorizationService authorizations;
    private final AuthSessionRepository attempts;
    private final AccountRepository accounts;
    private final ReauthProofRepository proofs;
    private final KakaoLoginProperties properties;
    private final KakaoLoginClient kakao;
    private final ServiceTokens tokens;
    private final IdempotentWrite idempotency;

    AuthReauthService(AuthAuthorizationService authorizations, AuthSessionRepository attempts,
            AccountRepository accounts, ReauthProofRepository proofs, KakaoLoginProperties properties,
            KakaoLoginClient kakao, ServiceTokens tokens, IdempotentWrite idempotency) {
        this.authorizations = authorizations;
        this.attempts = attempts;
        this.accounts = accounts;
        this.proofs = proofs;
        this.properties = properties;
        this.kakao = kakao;
        this.tokens = tokens;
        this.idempotency = idempotency;
    }

    public AuthorizationResponse start(String userId, String key, ReauthRequest request) {
        return idempotency.run(userId, key, List.of("start-reauth", request), AuthorizationResponse.class, () -> {
            accounts.findKakaoId(userId).orElseThrow(TokenExchangeException::notFound);
            return authorizations.startReauth(userId, request);
        });
    }

    public ReauthResponse complete(String userId, String key, TokenRequest request) {
        tokens.requireConfigured();
        String binding = "reauth:" + userId + "\n" + key;
        String sealed = idempotency.run(userId, key, List.of("complete-reauth", request), String.class,
                () -> tokens.seal(issueProof(userId, request), binding));
        return tokens.open(sealed, binding, ReauthResponse.class);
    }

    private ReauthResponse issueProof(String userId, TokenRequest request) {
        var attempt = attempts.lockById(request.authSessionId()).orElseThrow(TokenExchangeException::notFound);
        if (!"REAUTH".equals(attempt.session().purpose()) || !userId.equals(attempt.userId())) {
            throw TokenExchangeException.reauthMismatch();
        }
        if (!AuthTokenService.matchesAttempt(attempt.session(), request, properties)) {
            throw TokenExchangeException.reauthDenied();
        }
        if (attempt.consumed()) {
            throw TokenExchangeException.alreadyUsed();
        }
        if (!attempt.session().expiresAt().isAfter(Instant.now())) {
            throw TokenExchangeException.reauthDenied();
        }
        long expected = accounts.findKakaoId(userId).orElseThrow(TokenExchangeException::notFound);
        long actual = kakao.authenticate(request.authorizationCode(), attempt.session().redirectUri());
        if (actual != expected) {
            throw TokenExchangeException.reauthMismatch();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiry = now.plusSeconds(300);
        String proof = tokens.refresh();
        proofs.save(ServiceTokens.refreshHash(proof), userId, expiry);
        attempts.consume(attempt.session().id(), now);
        return new ReauthResponse(proof, expiry);
    }
}
