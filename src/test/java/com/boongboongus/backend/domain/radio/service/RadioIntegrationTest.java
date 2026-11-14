package com.boongboongus.backend.domain.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.boongboongus.backend.domain.job.dto.JobResponse.Status;
import com.boongboongus.backend.domain.job.service.JobService;
import com.boongboongus.backend.domain.radio.dto.RadioRequest;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest(properties = {"AUTH_TOKEN_KEY=radio-test-key", "app.auth.token-key=",
        "spring.config.import=classpath:legal-documents-test.yml"})
class RadioIntegrationTest {
    @Autowired RadioService radio;
    @Autowired JobService jobs;
    @Autowired JdbcClient jdbc;
    @MockitoBean RadioProviders providers;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Test
    void acceptsRadioJsonWithOmittedOptionalFields() {
        var story = json.readValue("""
                {"mode":"USER_STORY","story":"오늘의 사연"}
                """, RadioRequest.class);
        assertThat(story.driveId()).isNull();
        assertThat(story.story()).isEqualTo("오늘의 사연");
        var random = json.readValue("""
                {"mode":"RANDOM"}
                """, RadioRequest.class);
        assertThat(random.story()).isNull();
        assertThat(random.driveId()).isNull();
    }

    @Test
    void generatesPrivateAudioAndRejectsInvalidStory() throws InterruptedException {
        String owner = "usr_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed) VALUES (:id, -9301, '', FALSE)")
                .param("id", owner).update();
        when(providers.configured()).thenReturn(true);
        when(providers.script(any())).thenReturn("오늘의 방송입니다.");
        when(providers.synthesize("오늘의 방송입니다.")).thenReturn(new byte[]{1, 2, 3});
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> radio.create(owner, key, new RadioRequest(RadioRequest.Mode.RANDOM, "story", null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        var request = new RadioRequest(RadioRequest.Mode.USER_STORY, "  오늘의 사연  ", null);
        var job = radio.create(owner, key, request);
        assertThat(radio.create(owner, key, request).id()).isEqualTo(job.id());
        Instant deadline = Instant.now().plusSeconds(10);
        while ((job.status() == Status.QUEUED || job.status() == Status.RUNNING) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            job = jobs.get(owner, job.id());
        }
        assertThat(job.status()).isEqualTo(Status.SUCCEEDED);
        String id = job.resourceId();
        var result = radio.get(owner, id, "http://localhost");
        assertThat(result.story()).isEqualTo("오늘의 사연");
        assertThat(radio.history(owner, "사연", null, 1, "http://localhost").items())
                .extracting(episode -> episode.id()).containsExactly(id);
        assertThat(radio.history(owner, "방송", null, 1, "http://localhost").items()).hasSize(1);
        assertThat(radio.history(owner, "%", null, 1, "http://localhost").items()).isEmpty();
        assertThat(radio.history("another-user", null, null, 1, "http://localhost").items()).isEmpty();
        assertThatThrownBy(() -> radio.history(owner, null, "invalid", 1, "http://localhost"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");

        var second = radio.create(owner, UUID.randomUUID().toString(), request);
        var random = radio.create(owner, UUID.randomUUID().toString(),
                new RadioRequest(RadioRequest.Mode.RANDOM, null, null));
        Instant historyDeadline = Instant.now().plusSeconds(10);
        while ((second.status() != Status.SUCCEEDED || random.status() != Status.SUCCEEDED)
                && Instant.now().isBefore(historyDeadline)) {
            Thread.sleep(50);
            second = jobs.get(owner, second.id());
            random = jobs.get(owner, random.id());
        }
        assertThat(random.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(second.status()).isEqualTo(Status.SUCCEEDED);
        var firstPage = radio.history(owner, null, null, 1, "http://localhost");
        assertThat(firstPage.items()).extracting(episode -> episode.id()).containsExactly(second.resourceId());
        assertThat(firstPage.hasMore()).isTrue();
        var lastPage = radio.history(owner, null, firstPage.nextCursor(), 1, "http://localhost");
        assertThat(lastPage.items()).extracting(episode -> episode.id()).containsExactly(id);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.nextCursor()).isNull();
        var query = URI.create(result.audioUrl()).getQuery().split("&");
        long expires = Long.parseLong(query[0].substring("expires=".length()));
        String signature = query[1].substring("signature=".length());
        assertThat(radio.audio(owner, id, expires, signature)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> radio.audio("another-user", id, expires, signature))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> radio.get("another-user", id, "http://localhost"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
