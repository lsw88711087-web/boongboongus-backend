package com.boongboongus.backend.domain.radio.service;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.service.JobService;
import com.boongboongus.backend.domain.radio.dto.RadioEpisode;
import com.boongboongus.backend.domain.radio.dto.RadioHistory;
import com.boongboongus.backend.domain.radio.dto.RadioRequest;
import com.boongboongus.backend.domain.radio.repository.RadioRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RadioService {
    private static final String PATH = "/api/v1/radio/episodes/";
    private final RadioRepository radio;
    private final RadioProviders providers;
    private final DrivePreparationService drives;
    private final JobService jobs;
    private final IdempotentWrite idempotency;
    private final TransactionTemplate transaction;
    private final String signingKey;

    RadioService(RadioRepository radio, RadioProviders providers, DrivePreparationService drives, JobService jobs,
            IdempotentWrite idempotency, TransactionTemplate transaction, @Value("${AUTH_TOKEN_KEY:}") String signingKey) {
        this.radio = radio;
        this.providers = providers;
        this.drives = drives;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.transaction = transaction;
        this.signingKey = signingKey;
    }

    public JobResponse create(String owner, String key, RadioRequest input) {
        String story = input.story() == null ? null : input.story().strip();
        boolean validStory = story != null && !story.isEmpty() && story.codePointCount(0, story.length()) <= 300;
        if (input.mode() == RadioRequest.Mode.USER_STORY ? !validStory : story != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        var request = new RadioRequest(input.mode(), story, input.driveId());
        return idempotency.run(owner, key, List.of("create-radio", request), JobResponse.class, () -> {
            if (request.driveId() != null && !drives.canAccessPlaylistDrive(owner, request.driveId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (!providers.configured() || signingKey.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            var job = jobs.enqueue(owner, JobResponse.Kind.RADIO_GENERATION);
            radio.enqueue(job.id(), owner, request);
            return job;
        });
    }

    public RadioEpisode get(String owner, String id, String origin) {
        var episode = radio.find(owner, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return response(owner, origin, episode);
    }

    public RadioHistory history(String owner, String q, String cursor, int limit, String origin) {
        if (limit < 1 || limit > 100 || (q != null && q.codePointCount(0, q.length()) > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String query = q == null ? "" : q.strip();
        OffsetDateTime before = null;
        String beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 512) {
                    throw new IllegalArgumentException();
                }
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\\|", -1);
                if (parts.length != 3 || !owner.equals(parts[0]) || parts[2].isBlank() || parts[2].length() > 80) {
                    throw new IllegalArgumentException();
                }
                before = OffsetDateTime.parse(parts[1]);
                beforeId = parts[2];
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        var rows = radio.history(owner, query, before, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        String nextCursor = null;
        if (hasMore) {
            var last = page.getLast();
            String marker = owner + "|" + last.createdAt().atOffset(ZoneOffset.UTC) + "|" + last.id();
            nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(marker.getBytes(StandardCharsets.UTF_8));
        }
        return new RadioHistory(page.stream().map(episode -> response(owner, origin, episode)).toList(),
                nextCursor, hasMore);
    }

    private RadioEpisode response(String owner, String origin, RadioRepository.Episode episode) {
        String id = episode.id();
        long expires = Instant.now().plusSeconds(600).getEpochSecond();
        String audioUrl = origin + PATH + id + "/audio?expires=" + expires + "&signature=" + sign(owner, id, expires);
        return new RadioEpisode(id, episode.driveId(), episode.mode(), episode.story(), episode.script(), audioUrl,
                Instant.ofEpochSecond(expires), null, "READY", episode.createdAt());
    }

    public byte[] audio(String owner, String id, long expires, String signature) {
        if (expires <= Instant.now().getEpochSecond() || signingKey.isBlank()
                || !MessageDigest.isEqual(sign(owner, id, expires).getBytes(StandardCharsets.UTF_8),
                        signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return radio.audio(owner, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String sign(String owner, String id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    ("radio-audio\n" + owner + "\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot sign radio audio URL", exception);
        }
    }

    @Scheduled(fixedDelay = 2000)
    void generateQueued() {
        for (var pending : radio.pending()) {
            if (!jobs.start(pending.jobId())) {
                continue;
            }
            try {
                String script = providers.script(pending.request());
                byte[] audio = providers.synthesize(script);
                String id = "rad_" + UUID.randomUUID();
                transaction.executeWithoutResult(status -> {
                    if (jobs.succeed(pending.jobId(), id)) {
                        radio.ready(pending.jobId(), script, audio);
                    }
                });
            } catch (RuntimeException exception) {
                // Do not log provider payloads, which can contain the user's story.
                jobs.fail(pending.jobId(), new ErrorDetail("SERVICE_UNAVAILABLE",
                        "라디오 대본 또는 음성을 생성하지 못했습니다.", List.of(), true));
            }
        }
    }
}
