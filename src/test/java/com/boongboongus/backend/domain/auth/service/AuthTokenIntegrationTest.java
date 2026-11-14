package com.boongboongus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.auth.model.AuthSession;
import com.boongboongus.backend.domain.auth.repository.AuthSessionRepository;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.config.import=classpath:legal-documents-test.yml",
        "app.auth.kakao.rest-api-key=test-kakao-client",
        "app.auth.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.auth.kakao.redirect-uris[0]=https://app.example.invalid/callback"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTokenIntegrationTest {
    private static final String CALLBACK = "https://app.example.invalid/callback";
    private static final String VERIFIER = "A".repeat(43);

    @Autowired private MockMvc mvc;
    @Autowired private AuthSessionRepository attempts;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private ServiceTokens tokens;
    @MockitoBean private KakaoLoginClient kakao;

    @Test
    void validatesAttemptIssuesTokensAndReplaysOnlyMatchingRequest() throws Exception {
        String id = "auth_" + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ServiceTokens.sha256(VERIFIER));
        attempts.save(new AuthSession(id, "KAKAO", "LOGIN", CALLBACK, challenge, "matching-state",
                Instant.now().plusSeconds(600)));
        when(kakao.authenticate("provider-code", CALLBACK)).thenReturn(42L);
        String key = UUID.randomUUID().toString();

        mvc.perform(exchange(id, key, "wrong-state")).andExpect(status().isUnauthorized());
        var first = mvc.perform(exchange(id, key, "matching-state")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(jsonPath("$.data.user.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").value(1800))
                .andExpect(jsonPath("$.data.refreshTokenExpiresInSeconds").value(1209600))
                .andReturn().getResponse().getContentAsString();

        var data = json.readTree(first).get("data");
        String access = data.get("accessToken").asText();
        String refresh = data.get("refreshToken").asText();
        var me = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me");
        mvc.perform(me).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(me.header("Authorization", "Bearer " + access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(data.get("user").get("id").asText()))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(false));
        String expiredJwt = tokens.access(data.get("user").get("id").asText(), "session",
                Instant.now().minusSeconds(1801));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                .header("Authorization", "Bearer " + expiredJwt)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_TOKEN_EXPIRED"));
        String invalidJwt = (access.startsWith("A") ? "B" : "A") + access.substring(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                .header("Authorization", "Bearer " + invalidJwt)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        String[] jwt = access.split("[.]", -1);
        assertThat(jwt).hasSize(3);
        var payload = json.readTree(Base64.getUrlDecoder().decode(jwt[1]));
        assertThat(payload.get("sub").asText()).isEqualTo(data.get("user").get("id").asText());
        assertThat(payload.get("exp").asLong() - payload.get("iat").asLong()).isEqualTo(1800);
        assertThat(jdbc.sql("SELECT refresh_token_hash FROM auth_service_sessions WHERE id = :id")
                .param("id", payload.get("sid").asText()).query(String.class).single())
                .isEqualTo(ServiceTokens.refreshHash(refresh));
        assertThat(jdbc.sql("SELECT response_snapshot FROM api_idempotency WHERE request_key = :key")
                .param("key", key).query(String.class).single()).doesNotContain(refresh, access);

        mvc.perform(exchange(id, key, "matching-state")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value(access))
                .andExpect(jsonPath("$.data.refreshToken").value(refresh));
        mvc.perform(exchange(id, key, "wrong-state")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(exchange(id, UUID.randomUUID().toString(), "matching-state")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_CODE_ALREADY_USED"));
        verify(kakao).authenticate("provider-code", CALLBACK);
    }

    @Test
    void refreshReplaysMatchingRequestAndRevokesSessionOnReuse() throws Exception {
        String userId = "usr_" + UUID.randomUUID();
        String sessionId = "ses_" + UUID.randomUUID();
        String original = "refresh-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed) VALUES (:id, -42, '', FALSE)")
                .param("id", userId).update();
        jdbc.sql("""
                INSERT INTO auth_service_sessions (id, user_id, refresh_token_hash, expires_at)
                VALUES (:id, :user, :hash, :expiry)
                """).param("id", sessionId).param("user", userId).param("hash", ServiceTokens.refreshHash(original))
                .param("expiry", java.time.OffsetDateTime.now().plusDays(1)).update();
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(refresh(original, key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").value(1800))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(first).get("data");
        String next = data.get("refreshToken").asText();
        assertThat(next).isNotEqualTo(original);

        mvc.perform(refresh(original, key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").value(next))
                .andExpect(jsonPath("$.data.accessToken").value(data.get("accessToken").asText()));
        mvc.perform(refresh(next, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        jdbc.sql("UPDATE auth_refresh_rotations SET retry_until = :past WHERE session_id = :id")
                .param("past", java.time.OffsetDateTime.now().minusSeconds(1)).param("id", sessionId).update();
        mvc.perform(refresh(original, key)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(refresh(next, UUID.randomUUID().toString())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_EXPIRED"));
    }

    @Test
    void logoutRevokesOnlyTheMatchingSessionAndRemovesOnlyOwnedPushRegistration() throws Exception {
        String owner = createLogoutUser(-51);
        String other = createLogoutUser(-52);
        String first = createLogoutSession(owner, java.time.OffsetDateTime.now().plusDays(1));
        String second = createLogoutSession(owner, java.time.OffsetDateTime.now().plusDays(1));
        String expired = createLogoutSession(owner, java.time.OffsetDateTime.now().minusDays(1));
        String ownDevice = createPushRegistration(owner);
        String foreignDevice = createPushRegistration(other);
        String key = UUID.randomUUID().toString();

        mvc.perform(logout(first, ownDevice, key)).andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        mvc.perform(logout(first, ownDevice, key)).andExpect(status().isNoContent());
        mvc.perform(logout(first, foreignDevice, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(refresh(first, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(refresh(second, UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(logout(second, foreignDevice, UUID.randomUUID().toString())).andExpect(status().isNoContent());
        mvc.perform(logout(expired, ownDevice, UUID.randomUUID().toString())).andExpect(status().isNoContent());
        mvc.perform(logout("unknown", ownDevice, UUID.randomUUID().toString())).andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT device_id FROM device_push_tokens WHERE device_id IN (:own, :foreign)")
                .param("own", ownDevice).param("foreign", foreignDevice).query(String.class).list())
                .containsExactly(foreignDevice);
    }

    @Test
    void updatesOwnProfileAndRequiresCurrentConsentsBeforeCompletingOnboarding() throws Exception {
        String id = createLogoutUser(-71);
        String access = tokens.access(id, "session", Instant.now());
        String bearer = "Bearer " + access;
        var onboarding = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/me/onboarding")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/me")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);

        String body = """
                {"nickname":"  붕붕  ","birthDate":null,"gender":"FEMALE","mascotName":"붕붕이",
                 "consents":[{"type":"TERMS","version":"v2","agreed":true},
                 {"type":"PRIVACY","version":"v1","agreed":true}]}
                """;
        mvc.perform(onboarding.content(body.replace("v2", "v1"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("REQUIRED_CONSENT_MISSING"));
        mvc.perform(onboarding.content(body.replace("\"birthDate\":null,", ""))).andExpect(status().isBadRequest());
        mvc.perform(onboarding.content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("붕붕"))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(true));
        mvc.perform(onboarding.content(body)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM auth_user_consents WHERE user_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(2);
        mvc.perform(patch.content("{}")).andExpect(status().is(422));
        mvc.perform(patch.content("{\"birthDate\":\"2000-01-01\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("붕붕"))
                .andExpect(jsonPath("$.data.birthDate").value("2000-01-01"));
        mvc.perform(patch.content("{\"birthDate\":null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthDate").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/me")
                .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reauthBindsAttemptAndProofToTheSameAccount() throws Exception {
        String owner = createLogoutUser(-81);
        String other = createLogoutUser(-82);
        String ownerBearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        String otherBearer = "Bearer " + tokens.access(other, "session", Instant.now());
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ServiceTokens.sha256(VERIFIER));
        String input = """
                {"provider":"KAKAO","redirectUri":"%s","codeChallenge":"%s"}
                """.formatted(CALLBACK, challenge);
        var start = post("/api/v1/auth/reauth/authorizations").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(input);

        mvc.perform(start).andExpect(status().isUnauthorized());
        var attempt = json.readTree(mvc.perform(start.header("Authorization", ownerBearer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        String id = attempt.get("authSessionId").asText();
        String state = attempt.get("state").asText();
        assertThat(attempt.get("authorizationUrl").asText()).contains("prompt=login");

        mvc.perform(exchange(id, UUID.randomUUID().toString(), state)).andExpect(status().isUnauthorized());

        when(kakao.authenticate("other-kakao-code", CALLBACK)).thenReturn(-82L);
        when(kakao.authenticate("owner-kakao-code", CALLBACK)).thenReturn(-81L);
        mvc.perform(reauth(id, state, "owner-kakao-code", otherBearer, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_SUBJECT_MISMATCH"));
        mvc.perform(reauth(id, state, "other-kakao-code", ownerBearer, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REAUTH_SUBJECT_MISMATCH"));

        String key = UUID.randomUUID().toString();
        var proof = json.readTree(mvc.perform(reauth(id, state, "owner-kakao-code", ownerBearer, key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        String rawProof = proof.get("reauthToken").asText();
        assertThat(jdbc.sql("SELECT user_id FROM auth_reauth_proofs WHERE token_hash = :hash")
                .param("hash", ServiceTokens.refreshHash(rawProof)).query(String.class).single()).isEqualTo(owner);
        mvc.perform(reauth(id, state, "owner-kakao-code", ownerBearer, key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthToken").value(rawProof));
        mvc.perform(reauth(id, state, "owner-kakao-code", ownerBearer, UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reauth(
            String id, String state, String code, String bearer, String key) {
        return post("/api/v1/auth/reauth").header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"authSessionId":"%s","authorizationCode":"%s","state":"%s","codeVerifier":"%s"}
                        """.formatted(id, code, state, VERIFIER));
    }

    @Test
    void deletionChecksProofAndActiveDriveBeforeReportingUnavailable() throws Exception {
        String owner = createLogoutUser(-91);
        String other = createLogoutUser(-92);
        String proof = UUID.randomUUID().toString();
        String bearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                .header("Authorization", bearer);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTH_REQUIRED"));

        jdbc.sql("""
                INSERT INTO auth_reauth_proofs (token_hash, user_id, purpose, expires_at)
                VALUES (:hash, :user, 'ACCOUNT_DELETION', :expiry)
                """).param("hash", ServiceTokens.refreshHash(proof)).param("user", owner)
                .param("expiry", java.time.OffsetDateTime.now().plusMinutes(5)).update();

        String driveId = "drv_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO drives (id, couple_id, title, status, active_slot, driver_user_id, passenger_user_id,
                    recorder_user_id, control_version)
                VALUES (:id, :id, 'test', 'PREPARING', 1, :owner, :other, :owner, 1)
                """).param("id", driveId).param("owner", owner).param("other", other).update();
        request.header("X-Reauth-Token", proof);
        mvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_DRIVE_EXISTS"));

        jdbc.sql("UPDATE drives SET status = 'CANCELLED', active_slot = NULL WHERE id = :id")
                .param("id", driveId).update();
        mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.retryable").value(true));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM auth_reauth_proofs WHERE user_id = :id AND consumed_at IS NULL")
                .param("id", owner).query(Long.class).single()).isEqualTo(1);
    }

    private String createLogoutUser(long kakaoId) {
        String id = "usr_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed) VALUES (:id, :kakao, '', FALSE)")
                .param("id", id).param("kakao", kakaoId).update();
        return id;
    }

    private String createLogoutSession(String userId, java.time.OffsetDateTime expiry) {
        String token = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO auth_service_sessions (id, user_id, refresh_token_hash, expires_at)
                VALUES (:id, :user, :hash, :expiry)
                """).param("id", "ses_" + UUID.randomUUID()).param("user", userId)
                .param("hash", ServiceTokens.refreshHash(token)).param("expiry", expiry).update();
        return token;
    }

    private String createPushRegistration(String userId) {
        String id = "dev_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO device_push_tokens (device_id, user_id, push_token) VALUES (:id, :user, :token)")
                .param("id", id).param("user", userId).param("token", "push-" + id).update();
        return id;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logout(
            String token, String deviceId, String key) {
        return post("/api/v1/auth/logout").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token, "deviceId", deviceId)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refresh(String token, String key) {
        return post("/api/v1/auth/refresh").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exchange(
            String id, String key, String state) {
        return post("/api/v1/auth/token").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"authSessionId":"%s","authorizationCode":"provider-code","state":"%s","codeVerifier":"%s"}
                        """.formatted(id, state, VERIFIER));
    }
}
