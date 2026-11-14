package com.boongboongus.backend.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ServiceTokens {
    static final int ACCESS_SECONDS = 1800;
    static final int REFRESH_SECONDS = 1209600;
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json;
    private final byte[] key;

    ServiceTokens(JsonMapper json, @Value("${app.auth.token-key:}") String encodedKey) {
        this.json = json;
        this.key = encodedKey.isBlank() ? new byte[0] : Base64.getDecoder().decode(encodedKey);
        if (key.length > 0 && key.length < 32) {
            throw new IllegalArgumentException("AUTH_TOKEN_KEY must encode at least 32 random bytes");
        }
    }

    void requireConfigured() {
        if (key.length == 0) {
            throw TokenExchangeException.unavailable();
        }
    }

    String access(String userId, String sessionId, Instant issuedAt) {
        String header = URL.encodeToString(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = URL.encodeToString(json.writeValueAsBytes(Map.of(
                "iss", "boongboongus", "sub", userId, "sid", sessionId, "type", "access",
                "jti", UUID.randomUUID().toString(), "iat", issuedAt.getEpochSecond(),
                "exp", issuedAt.getEpochSecond() + ACCESS_SECONDS)));
        String input = header + "." + payload;
        return input + "." + URL.encodeToString(mac(key, input));
    }

    String accessSubject(String token) {
        if (key.length == 0 || token.length() > 4096) {
            return null;
        }
        String[] parts = token.split("[.]", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            var decoder = Base64.getUrlDecoder();
            if (!MessageDigest.isEqual(mac(key, parts[0] + "." + parts[1]), decoder.decode(parts[2]))) {
                return null;
            }
            var header = json.readTree(decoder.decode(parts[0]));
            var claims = json.readTree(decoder.decode(parts[1]));
            var subject = claims.path("sub");
            var expiry = claims.path("exp");
            if (!"HS256".equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())
                    || !"boongboongus".equals(claims.path("iss").asText())
                    || !"access".equals(claims.path("type").asText())
                    || !subject.isTextual() || subject.asText().isBlank()
                    || !expiry.isIntegralNumber() || !expiry.canConvertToLong()) {
                return null;
            }
            if (expiry.asLong() <= Instant.now().getEpochSecond()) {
                throw new ExpiredAccessToken();
            }
            return subject.asText();
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
            return null;
        }
    }

    static final class ExpiredAccessToken extends RuntimeException {
    }

    String refresh() {
        return URL.encodeToString(randomBytes(32));
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String refreshHash(String refresh) {
        return HexFormat.of().formatHex(sha256(refresh));
    }

    String seal(Object response, String binding) {
        byte[] nonce = randomBytes(12);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, json.writeValueAsBytes(response), binding);
        return URL.encodeToString(nonce) + "." + URL.encodeToString(ciphertext);
    }

    <T> T open(String sealed, String binding, Class<T> type) {
        String[] parts = sealed.split("[.]", 2);
        var decoder = Base64.getUrlDecoder();
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, decoder.decode(parts[0]), decoder.decode(parts[1]), binding);
        return json.readValue(plaintext, type);
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input, String binding) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] replayKey = mac(key, "boongboongus/token-response-replay/v1");
            cipher.init(mode, new SecretKeySpec(replayKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Token response encryption failed", exception);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] mac(byte[] secret, String input) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC signing failed", exception);
        }
    }
}
