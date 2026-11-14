package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.boongboongus.backend.domain.auth.dto.TokenRequest;
import com.boongboongus.backend.domain.auth.dto.TokenResponse;
import com.boongboongus.backend.domain.auth.model.ServiceSession;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.auth.repository.AuthSessionRepository;
import com.boongboongus.backend.domain.auth.repository.ServiceSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private final AuthSessionRepository attempts;
    private final AccountRepository accounts;
    private final ServiceSessionRepository sessions;
    private final KakaoLoginProperties properties;
    private final KakaoLoginClient kakao;
    private final ServiceTokens tokens;
    private final IdempotentWrite idempotency;

    AuthTokenService(AuthSessionRepository attempts, AccountRepository accounts, ServiceSessionRepository sessions,
            KakaoLoginProperties properties, KakaoLoginClient kakao, ServiceTokens tokens, IdempotentWrite idempotency) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.sessions = sessions;
        this.properties = properties;
        this.kakao = kakao;
        this.tokens = tokens;
        this.idempotency = idempotency;
    }

    public TokenResponse exchange(String key, TokenRequest request) {
        tokens.requireConfigured();
        String scope = "login:" + ServiceTokens.refreshHash(request.authSessionId());
        // The existing idempotency transaction stores only the encrypted response.
        String binding = scope + "\n" + key;
        String sealed = idempotency.run(scope, key, List.of("exchange-login-token", request), String.class,
                () -> tokens.seal(issue(request), binding));
        return tokens.open(sealed, binding, TokenResponse.class);
    }

    private TokenResponse issue(TokenRequest request) {
        var attempt = attempts.lockById(request.authSessionId()).orElseThrow(TokenExchangeException::notFound);
        var session = attempt.session();
        if (!"LOGIN".equals(session.purpose()) || !matchesAttempt(session, request, properties)) {
            throw TokenExchangeException.verificationFailed();
        }
        if (attempt.consumed()) {
            throw TokenExchangeException.alreadyUsed();
        }
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw TokenExchangeException.verificationFailed();
        }

        long kakaoId = kakao.authenticate(request.authorizationCode(), session.redirectUri());
        var account = accounts.findOrCreateKakaoUser(kakaoId);
        Instant now = Instant.now();
        String sessionId = "ses_" + UUID.randomUUID();
        String refresh = tokens.refresh();
        sessions.save(new ServiceSession(sessionId, account.user().id(), ServiceTokens.refreshHash(refresh),
                now.plusSeconds(ServiceTokens.REFRESH_SECONDS)));
        attempts.consume(session.id(), now);
        return new TokenResponse(tokens.access(account.user().id(), sessionId, now), ServiceTokens.ACCESS_SECONDS,
                refresh, ServiceTokens.REFRESH_SECONDS, "Bearer", account.user(), account.created());
    }

    static boolean matchesAttempt(com.boongboongus.backend.domain.auth.model.AuthSession session,
            TokenRequest request, KakaoLoginProperties properties) {
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ServiceTokens.sha256(request.codeVerifier()));
        return "KAKAO".equals(session.provider()) && properties.redirectUris().contains(session.redirectUri())
                && matches(session.state(), request.state()) && matches(session.codeChallenge(), challenge);
    }

    private static boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
