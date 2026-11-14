package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.boongboongus.backend.domain.auth.dto.AuthorizationRequest;
import com.boongboongus.backend.domain.auth.dto.AuthorizationResponse;
import com.boongboongus.backend.domain.auth.dto.ReauthRequest;
import com.boongboongus.backend.domain.auth.model.AuthSession;
import com.boongboongus.backend.domain.auth.repository.AuthSessionRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AuthAuthorizationService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final String KAKAO_AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";

    private final AuthSessionRepository sessions;
    private final KakaoLoginProperties kakao;
    private final SecureRandom random = new SecureRandom();

    AuthAuthorizationService(AuthSessionRepository sessions, KakaoLoginProperties kakao) {
        this.sessions = sessions;
        this.kakao = kakao;
    }

    public AuthorizationResponse startLogin(AuthorizationRequest request) {
        return start(request.provider(), request.redirectUri(), request.codeChallenge(), request.purpose(), null);
    }

    AuthorizationResponse startReauth(String userId, ReauthRequest request) {
        return start(request.provider(), request.redirectUri(), request.codeChallenge(), "REAUTH", userId);
    }

    private AuthorizationResponse start(String provider, URI redirect, String challenge, String purpose, String userId) {
        if (!StringUtils.hasText(kakao.restApiKey())) {
            throw new AuthorizationException("AUTH_PROVIDER_DISABLED", "카카오 로그인이 비활성화되어 있습니다.");
        }

        String redirectUri = redirect.toString();
        if (!redirect.isAbsolute() || redirect.isOpaque() || redirect.getRawFragment() != null
                || !kakao.redirectUris().contains(redirectUri)) {
            throw new AuthorizationException("REDIRECT_URI_NOT_ALLOWED", "허용되지 않은 로그인 콜백 주소입니다.");
        }

        byte[] stateBytes = new byte[32];
        random.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        Instant expiresAt = Instant.now().plus(SESSION_TTL).truncatedTo(ChronoUnit.MICROS);
        AuthSession session = new AuthSession("auth_" + UUID.randomUUID(), provider, purpose,
                redirectUri, challenge, state, expiresAt);

        var url = UriComponentsBuilder.fromUriString(KAKAO_AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("state", "{state}");
        if (userId != null) {
            url.queryParam("prompt", "login");
        }
        URI authorizationUrl = url.encode()
                .buildAndExpand(kakao.restApiKey(), redirectUri, state)
                .toUri();

        sessions.save(session, userId);
        return new AuthorizationResponse(session.id(), authorizationUrl, state, expiresAt);
    }

    public static final class AuthorizationException extends RuntimeException {
        private final String code;

        AuthorizationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
