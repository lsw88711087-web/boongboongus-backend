package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.dto.RefreshTokenResponse;
import com.boongboongus.backend.domain.auth.model.ServiceSession;
import com.boongboongus.backend.domain.auth.repository.ServiceSessionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthRefreshService {
    private final ServiceSessionRepository sessions;
    private final ServiceTokens tokens;
    private final TransactionTemplate transaction;

    AuthRefreshService(ServiceSessionRepository sessions, ServiceTokens tokens, TransactionTemplate transaction) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.transaction = transaction;
    }

    public RefreshTokenResponse refresh(String key, String refreshToken) {
        tokens.requireConfigured();
        String hash = ServiceTokens.refreshHash(refreshToken);
        var response = transaction.execute(status -> rotate(key, hash));
        if (response == null) {
            // Report reuse after committing the revocation.
            throw TokenExchangeException.refreshReused();
        }
        return response;
    }

    private RefreshTokenResponse rotate(String key, String hash) {
        String id = sessions.findIdByRefreshHash(hash).orElseThrow(TokenExchangeException::refreshExpired);
        var stored = sessions.lockById(id).orElseThrow(TokenExchangeException::refreshExpired);
        var session = stored.session();
        Instant now = Instant.now();
        if (stored.revoked() || !session.expiresAt().isAfter(now)) {
            throw TokenExchangeException.refreshExpired();
        }

        String binding = "refresh:" + id + ":" + hash + "\n" + key;
        if (!session.refreshTokenHash().equals(hash)) {
            var previous = sessions.findRotation(hash).orElseThrow(TokenExchangeException::refreshExpired);
            if (previous.requestKey().equals(key) && previous.retryUntil().isAfter(now)) {
                return tokens.open(previous.sealedResponse(), binding, RefreshTokenResponse.class);
            }
            sessions.revoke(id, now);
            return null;
        }

        if (sessions.hasRotationKey(id, key)) {
            throw new IdempotentWrite.KeyReused();
        }
        String refresh = tokens.refresh();
        var response = new RefreshTokenResponse(tokens.access(session.userId(), id, now), ServiceTokens.ACCESS_SECONDS,
                refresh, ServiceTokens.REFRESH_SECONDS, "Bearer");
        sessions.recordRotation(id, hash, key, now.plusSeconds(30), tokens.seal(response, binding));
        sessions.rotate(new ServiceSession(id, session.userId(), ServiceTokens.refreshHash(refresh),
                now.plusSeconds(ServiceTokens.REFRESH_SECONDS)));
        return response;
    }
}
