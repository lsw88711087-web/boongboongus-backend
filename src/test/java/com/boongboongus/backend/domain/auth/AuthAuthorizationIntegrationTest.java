package com.boongboongus.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.auth.model.AuthSession;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "app.auth.kakao.rest-api-key=test-kakao-client",
        "app.auth.kakao.redirect-uris[0]=https://app.example.invalid/callback?source=a+b"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAuthorizationIntegrationTest {
    private static final String PATH = "/api/v1/auth/authorizations";
    private static final String REDIRECT_URI = "https://app.example.invalid/callback?source=a+b";
    private static final String CHALLENGE = "A".repeat(43);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;

    @Test
    void createsAndPersistsEachAuthorizationAttempt() throws Exception {
        Instant before = Instant.now();
        var response = json.readTree(start(REDIRECT_URI, CHALLENGE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andReturn().getResponse().getContentAsString()).get("data");
        String id = response.get("authSessionId").asText();
        String state = response.get("state").asText();
        Instant expiry = Instant.parse(response.get("expiresAt").asText());
        URI url = URI.create(response.get("authorizationUrl").asText());

        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        assertThat(expiry).isBetween(before.plusSeconds(600), Instant.now().plusSeconds(600));
        assertThat(url.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(url.getPath()).isEqualTo("/oauth/authorize");
        assertThat(url.getRawQuery()).contains("response_type=code", "client_id=test-kakao-client",
                "state=" + state, "%2B");
        assertThat(URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8))
                .contains("redirect_uri=" + REDIRECT_URI);

        AuthSession stored = jdbc.sql("SELECT * FROM auth_sessions WHERE id = :id").param("id", id)
                .query((row, index) -> new AuthSession(row.getString("id"), row.getString("provider"),
                        row.getString("purpose"), row.getString("redirect_uri"), row.getString("code_challenge"),
                        row.getString("state"), row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .single();
        assertThat(stored).isEqualTo(new AuthSession(id, "KAKAO", "LOGIN", REDIRECT_URI, CHALLENGE, state, expiry));

        start(REDIRECT_URI, CHALLENGE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authSessionId").value(org.hamcrest.Matchers.not(id)))
                .andExpect(jsonPath("$.data.state").value(org.hamcrest.Matchers.not(state)));
    }

    @Test
    void rejectsInvalidInputAndUnregisteredRedirectBeforeSaving() throws Exception {
        long before = jdbc.sql("SELECT COUNT(*) FROM auth_sessions").query(Long.class).single();

        start(REDIRECT_URI, "too-short").andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        start("https://app.example.invalid/other", CHALLENGE).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("REDIRECT_URI_NOT_ALLOWED"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM auth_sessions").query(Long.class).single()).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.ResultActions start(String redirect, String challenge) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"provider":"KAKAO","purpose":"LOGIN","redirectUri":"%s","codeChallenge":"%s"}
                """.formatted(redirect, challenge)));
    }
}
