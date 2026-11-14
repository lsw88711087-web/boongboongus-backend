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
    @Autowired private com.boongboongus.backend.domain.job.service.JobService jobs;
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

    @Test
    void currentCoupleDistinguishesUnlinkedDisconnectedAndConnected() throws Exception {
        String user = createLogoutUser(-101);
        String partner = createLogoutUser(-102);
        String bearer = "Bearer " + tokens.access(user, "session", Instant.now());
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/couples/me");

        mvc.perform(request).andExpect(status().isUnauthorized());
        request.header("Authorization", bearer);
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("UNLINKED"))
                .andExpect(jsonPath("$.data.couple").value(org.hamcrest.Matchers.nullValue()));

        String couple = "cpl_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :user, :partner, :date, '붕붕이')
                """).param("id", couple).param("user", user).param("partner", partner)
                .param("date", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))).update();

        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.data.couple.id").value(couple))
                .andExpect(jsonPath("$.data.couple.daysTogether").value(1))
                .andExpect(jsonPath("$.data.couple.members[0].id").value(user))
                .andExpect(jsonPath("$.data.couple.members[1].id").value(partner));
        jdbc.sql("UPDATE couples SET disconnected_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", couple).update();

        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("DISCONNECTED"))
                .andExpect(jsonPath("$.data.couple").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void issuesOneActiveInviteAndReplacesExpiredInvite() throws Exception {
        String user = createLogoutUser(-111);
        String bearer = "Bearer " + tokens.access(user, "session", Instant.now());
        String firstKey = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/couple-invites").header("Idempotency-Key", firstKey))
                .andExpect(status().isUnauthorized());
        var first = json.readTree(mvc.perform(post("/api/v1/couple-invites")
                .header("Authorization", bearer).header("Idempotency-Key", firstKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString()).get("data");
        String id = first.get("inviteId").asText();
        String code = first.get("code").asText();
        for (String key : java.util.List.of(firstKey, UUID.randomUUID().toString())) {
            mvc.perform(post("/api/v1/couple-invites").header("Authorization", bearer).header("Idempotency-Key", key))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.inviteId").value(id))
                    .andExpect(jsonPath("$.data.code").value(code));
        }

        jdbc.sql("UPDATE couple_invites SET expires_at = :past WHERE id = :id")
                .param("past", java.time.OffsetDateTime.now().minusSeconds(1)).param("id", id).update();
        mvc.perform(post("/api/v1/couple-invites").header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.inviteId").value(org.hamcrest.Matchers.not(id)));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM couple_invites WHERE owner_user_id = :id AND active_slot = 1")
                .param("id", user).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void getsOnlyOwnUnexpiredInvite() throws Exception {
        String user = createLogoutUser(-112);
        String other = createLogoutUser(-113);
        String bearer = "Bearer " + tokens.access(user, "session", Instant.now());
        String otherBearer = "Bearer " + tokens.access(other, "session", Instant.now());
        String path = "/api/v1/couple-invites/me";
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        mvc.perform(get).andExpect(status().isUnauthorized());
        mvc.perform(get.header("Authorization", bearer)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITE_NOT_FOUND"));

        var created = json.readTree(mvc.perform(post("/api/v1/couple-invites")
                .header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(get).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(created.get("code").asText()))
                .andExpect(jsonPath("$.data.expiresAt").value(created.get("expiresAt").asText()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", otherBearer)).andExpect(status().isNotFound());

        jdbc.sql("UPDATE couple_invites SET expires_at = :past WHERE owner_user_id = :user")
                .param("past", java.time.OffsetDateTime.now().minusSeconds(1)).param("user", user).update();
        mvc.perform(get).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITE_NOT_FOUND"));
    }

    @Test
    void connectsBothMembersAndConsumesInviteExactlyOnce() throws Exception {
        String owner = createLogoutUser(-114);
        String guest = createLogoutUser(-115);
        String outsider = createLogoutUser(-116);
        String ownerToken = "Bearer " + tokens.access(owner, "session", Instant.now());
        String guestToken = "Bearer " + tokens.access(guest, "session", Instant.now());
        String outsiderToken = "Bearer " + tokens.access(outsider, "session", Instant.now());
        jdbc.sql("UPDATE auth_users SET mascot_name = '발급자 이름' WHERE id = :id").param("id", owner).update();
        jdbc.sql("UPDATE auth_users SET mascot_name = '참가자 이름' WHERE id = :id").param("id", guest).update();

        var issued = json.readTree(mvc.perform(post("/api/v1/couple-invites").header("Authorization", ownerToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data");
        String code = issued.get("code").asText();
        mvc.perform(connect(ownerToken, UUID.randomUUID().toString(), code)).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("SELF_INVITE"));

        String key = UUID.randomUUID().toString();
        var created = json.readTree(mvc.perform(connect(guestToken, key, " " + code.toUpperCase(java.util.Locale.ROOT) + " "))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.mascotName").value("발급자 이름"))
                .andExpect(jsonPath("$.data.daysTogether").value(1))
                .andReturn().getResponse().getContentAsString()).get("data");
        String coupleId = created.get("id").asText();
        mvc.perform(connect(guestToken, key, code)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(coupleId));
        mvc.perform(connect(outsiderToken, UUID.randomUUID().toString(), code)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITE_CONSUMED"));

        for (String bearer : java.util.List.of(ownerToken, guestToken)) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/couples/me")
                    .header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.connectionStatus").value("CONNECTED"))
                    .andExpect(jsonPath("$.data.couple.id").value(coupleId));
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder connect(
            String bearer, String key, String code) {
        return post("/api/v1/couples").header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("code", code)));
    }

    @Test
    void disconnectRejectsActiveDriveAndKeepsNewConnectionOnStaleRetry() throws Exception {
        String user = createLogoutUser(-117);
        String partner = createLogoutUser(-118);
        String outsider = createLogoutUser(-119);
        String couple = "cpl_" + UUID.randomUUID();
        String bearer = "Bearer " + tokens.access(user, "session", Instant.now());
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :user, :partner, CURRENT_DATE, '붕붕이')
                """).param("id", couple).param("user", user).param("partner", partner).update();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/couples/me")
                .header("Authorization", bearer).header("X-Couple-Id", couple);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/couples/me")
                .header("Authorization", "Bearer " + tokens.access(outsider, "session", Instant.now()))
                .header("X-Couple-Id", couple)).andExpect(status().isForbidden());
        String drive = "drv_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO drives (id, couple_id, title, status, active_slot, driver_user_id, passenger_user_id,
                    recorder_user_id, control_version)
                VALUES (:id, :couple, 'test', 'PREPARING', 1, :user, :partner, :user, 1)
                """).param("id", drive).param("couple", couple).param("user", user).param("partner", partner).update();
        mvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_DRIVE_EXISTS"));
        jdbc.sql("UPDATE drives SET status = 'CANCELLED', active_slot = NULL WHERE id = :id").param("id", drive).update();
        mvc.perform(request).andExpect(status().isNoContent());
        mvc.perform(request).andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/couples/me")
                .header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionStatus").value("DISCONNECTED"));
        String newCouple = "cpl_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :user, :partner, CURRENT_DATE, '새 관계')
                """).param("id", newCouple).param("user", user).param("partner", outsider).update();
        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_CONTEXT_CHANGED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM couples WHERE id = :id AND disconnected_at IS NULL")
                .param("id", newCouple).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void restoresOnlySamePairAndPreservesPreviousCoupleValues() throws Exception {
        String owner = createLogoutUser(-120);
        String partner = createLogoutUser(-121);
        String other = createLogoutUser(-122);
        String ownerBearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        String partnerBearer = "Bearer " + tokens.access(partner, "session", Instant.now());
        String otherBearer = "Bearer " + tokens.access(other, "session", Instant.now());
        String priorId = "cpl_" + UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name,
                    version, disconnected_at)
                VALUES (:id, :owner, :partner, DATE '2000-01-01', '기존 이름', 4, CURRENT_TIMESTAMP)
                """).param("id", priorId).param("owner", owner).param("partner", partner).update();
        jdbc.sql("UPDATE auth_users SET mascot_name = '새 후보' WHERE id = :id").param("id", owner).update();

        String code = json.readTree(mvc.perform(post("/api/v1/couple-invites").header("Authorization", ownerBearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data").get("code").asText();
        mvc.perform(restore(otherBearer, UUID.randomUUID().toString(), code)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUPLE_HISTORY_NOT_FOUND"));
        mvc.perform(connect(partnerBearer, UUID.randomUUID().toString(), code)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COUPLE_RESTORE_REQUIRED"));

        String key = UUID.randomUUID().toString();
        mvc.perform(restore(partnerBearer, key, code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(priorId))
                .andExpect(jsonPath("$.data.startDate").value("2000-01-01"))
                .andExpect(jsonPath("$.data.mascotName").value("기존 이름"))
                .andExpect(jsonPath("$.data.version").value(5));
        mvc.perform(restore(partnerBearer, key, code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));
        mvc.perform(restore(partnerBearer, UUID.randomUUID().toString(), code)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITE_CONSUMED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder restore(
            String bearer, String key, String code) {
        return post("/api/v1/couples/restore").header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("code", code)));
    }

    @Test
    void updatesCoupleStartDateForEitherMemberWithCurrentVersion() throws Exception {
        String user = createLogoutUser(-123);
        String partner = createLogoutUser(-124);
        String outsider = createLogoutUser(-125);
        String couple = "cpl_" + UUID.randomUUID();
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :user, :partner, :date, '붕붕이')
                """).param("id", couple).param("user", user).param("partner", partner).param("date", today).update();
        var yesterday = today.minusDays(1);

        mvc.perform(patchStartDate(null, couple, yesterday, 1)).andExpect(status().isUnauthorized());
        mvc.perform(patchStartDate(outsider, couple, yesterday, 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_REQUIRED"));
        mvc.perform(patchStartDate(user, "old-couple", yesterday, 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_CONTEXT_CHANGED"));
        mvc.perform(patchStartDate(user, couple, today.plusDays(1), 1)).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("INVALID_START_DATE"));
        mvc.perform(patchStartDate(user, couple, yesterday, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value(yesterday.toString()))
                .andExpect(jsonPath("$.data.daysTogether").value(2))
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(patchStartDate(partner, couple, today, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(patchStartDate(partner, couple, today, 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daysTogether").value(1))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchStartDate(
            String user, String couple, java.time.LocalDate date, int version) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/couples/me")
                .header("X-Couple-Id", couple).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("startDate", date, "expectedVersion", version)));
        return user == null ? request : request.header("Authorization", "Bearer " + tokens.access(user, "session", Instant.now()));
    }

    @Test
    void mascotNameUpdateSharesVersionWithStartDateUpdate() throws Exception {
        String user = createLogoutUser(-126);
        String partner = createLogoutUser(-127);
        String couple = "cpl_" + UUID.randomUUID();
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :user, :partner, :date, '붕붕이')
                """).param("id", couple).param("user", user).param("partner", partner).param("date", today).update();

        mvc.perform(patchMascotName(null, couple, "달콩이", 1)).andExpect(status().isUnauthorized());
        mvc.perform(patchMascotName(user, couple, "가".repeat(21), 1)).andExpect(status().is(422));
        mvc.perform(patchMascotName(user, couple, "  달콩이  ", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mascotName").value("달콩이"))
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(patchStartDate(partner, couple, today, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(patchMascotName(partner, couple, "별이", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mascotName").value("별이"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchMascotName(
            String user, String couple, String name, int version) {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/couples/me/mascot-name").header("X-Couple-Id", couple)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", name, "expectedVersion", version)));
        return user == null ? request : request.header("Authorization", "Bearer " + tokens.access(user, "session", Instant.now()));
    }

    @Test
    void replacesDeviceRegistrationAndOnlyLetsCurrentOwnerDeleteIt() throws Exception {
        String first = createLogoutUser(-128);
        String second = createLogoutUser(-129);
        String firstBearer = "Bearer " + tokens.access(first, "session", Instant.now());
        String secondBearer = "Bearer " + tokens.access(second, "session", Instant.now());
        String device = "dev_" + UUID.randomUUID();
        String path = "/api/v1/devices/" + device + "/push-token";
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"test-push","platform":"ANDROID","enabled":false}
                        """);
        mvc.perform(put).andExpect(status().isUnauthorized());
        mvc.perform(put.header("Authorization", firstBearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header("Authorization", secondBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"replacement","platform":"IOS","enabled":true}
                        """)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", firstBearer)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT user_id FROM device_push_tokens WHERE device_id = :id")
                .param("id", device).query(String.class).single()).isEqualTo(second);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", secondBearer)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT user_id FROM device_push_tokens WHERE device_id = :id")
                .param("id", device).query(String.class).optional()).isEmpty();
    }

    @Test
    void revokesOnlyOwnedUnusedInvite() throws Exception {
        String owner = createLogoutUser(-130);
        String other = createLogoutUser(-131);
        String ownerBearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        String otherBearer = "Bearer " + tokens.access(other, "session", Instant.now());
        var invite = json.readTree(mvc.perform(post("/api/v1/couple-invites").header("Authorization", ownerBearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data");
        String path = "/api/v1/couple-invites/" + invite.get("inviteId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", otherBearer)).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", ownerBearer)).andExpect(status().isNoContent());
        mvc.perform(connect(otherBearer, UUID.randomUUID().toString(), invite.get("code").asText()))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("INVALID_INVITE"));
    }

    @Test
    void listsOnlyUnexpiredOwnedNotificationsAndKeepsFirstReadTime() throws Exception {
        String owner = createLogoutUser(-132);
        String other = createLogoutUser(-133);
        String bearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        var now = java.time.OffsetDateTime.now();
        String first = createNotification(owner, other, now.minusMinutes(1), now.plusDays(29));
        String second = createNotification(owner, null, now.minusMinutes(2), now.plusDays(29));
        String expired = createNotification(owner, null, now.minusDays(31), now.minusDays(1));
        String foreign = createNotification(other, owner, now, now.plusDays(30));

        String path = "/api/v1/notifications";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(status().isUnauthorized());
        var page = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer).param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(first))
                .andExpect(jsonPath("$.data.items[0].sender.id").value(other))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andReturn().getResponse().getContentAsString()).get("data");
        String cursor = page.get("nextCursor").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer).param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second))
                .andExpect(jsonPath("$.data.items[0].sender").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer).param("cursor", cursor).param("unreadOnly", "true"))
                .andExpect(status().isBadRequest());

        for (String unavailable : java.util.List.of(foreign, expired)) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    path + "/" + unavailable + "/read").header("Authorization", bearer))
                    .andExpect(status().isNotFound());
        }
        var read = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path + "/" + first + "/read")
                .header("Authorization", bearer);
        String readAt = json.readTree(mvc.perform(read).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("readAt").asText();
        assertThat(Instant.parse(readAt)).isBeforeOrEqualTo(Instant.now());
        mvc.perform(read).andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").value(readAt));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer).param("unreadOnly", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second));
    }

    private String createNotification(String recipient, String sender, java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime expiresAt) {
        String id = "ntf_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications (id, recipient_user_id, sender_user_id, type, title, body,
                    target_type, target_id, created_at, expires_at)
                VALUES (:id, :recipient, :sender, 'PLACE_SHARED', '장소 공유', '장소를 확인해 보세요.',
                    'PLACE', 'plc_test', :createdAt, :expiresAt)
                """).param("id", id).param("recipient", recipient).param("sender", sender, java.sql.Types.VARCHAR)
                .param("createdAt", createdAt).param("expiresAt", expiresAt).update();
        return id;
    }

    @Test
    void coupleEventsAppearOnceAndHomeReportsUnreadCount() throws Exception {
        String owner = createLogoutUser(-134);
        String guest = createLogoutUser(-135);
        String ownerBearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        String guestBearer = "Bearer " + tokens.access(guest, "session", Instant.now());
        String code = json.readTree(mvc.perform(post("/api/v1/couple-invites").header("Authorization", ownerBearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data").get("code").asText();
        String key = UUID.randomUUID().toString();
        String couple = json.readTree(mvc.perform(connect(guestBearer, key, code)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data").get("id").asText();
        mvc.perform(connect(guestBearer, key, code)).andExpect(status().isCreated());

        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/notifications")
                .header("Authorization", ownerBearer);
        String id = json.readTree(mvc.perform(get).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("COUPLE_CONNECTED"))
                .andReturn().getResponse().getContentAsString()).get("data").get("items").get(0).get("id").asText();
        var home = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/home")
                .header("Authorization", ownerBearer);
        mvc.perform(home).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadNotificationCount").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/api/v1/notifications/" + id + "/read").header("Authorization", ownerBearer))
                .andExpect(status().isOk());
        mvc.perform(home).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadNotificationCount").value(0));

        var disconnect = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/couples/me")
                .header("Authorization", guestBearer).header("X-Couple-Id", couple);
        mvc.perform(disconnect).andExpect(status().isNoContent());
        mvc.perform(disconnect).andExpect(status().isNoContent());
        mvc.perform(get).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].type").value("COUPLE_DISCONNECTED"));
    }

    @Test
    void cancelsOnlyOwnedJobAndRejectsLateCompletion() throws Exception {
        String owner = createLogoutUser(-136);
        String other = createLogoutUser(-137);
        String ownerBearer = "Bearer " + tokens.access(owner, "session", Instant.now());
        String otherBearer = "Bearer " + tokens.access(other, "session", Instant.now());
        var kind = com.boongboongus.backend.domain.job.dto.JobResponse.Kind.PLAYLIST_GENERATION;
        var job = jobs.enqueue(owner, kind);
        String path = "/api/v1/jobs/" + job.id();
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);

        mvc.perform(get).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", otherBearer)).andExpect(status().isNotFound());
        mvc.perform(get.header("Authorization", ownerBearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        assertThat(jobs.start(job.id())).isTrue();
        String key = UUID.randomUUID().toString();
        mvc.perform(post(path + "/cancel").header("Authorization", otherBearer).header("Idempotency-Key", key))
                .andExpect(status().isNotFound());
        var cancel = post(path + "/cancel").header("Authorization", ownerBearer).header("Idempotency-Key", key);
        mvc.perform(cancel).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"));
        mvc.perform(cancel).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCEL_REQUESTED"));
        assertThat(jobs.succeed(job.id(), "ply_late")).isFalse();
        mvc.perform(get).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value(org.hamcrest.Matchers.nullValue()));

        var completed = jobs.enqueue(owner, kind);
        assertThat(jobs.start(completed.id())).isTrue();
        assertThat(jobs.succeed(completed.id(), "ply_success")).isTrue();
        mvc.perform(post("/api/v1/jobs/" + completed.id() + "/cancel")
                .header("Authorization", ownerBearer).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_ALREADY_FINISHED"));

        jdbc.sql("UPDATE ai_jobs SET expires_at = :past WHERE id = :id")
                .param("id", job.id()).param("past", java.time.OffsetDateTime.now().minusSeconds(1)).update();
        mvc.perform(get).andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("JOB_EXPIRED"));
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
