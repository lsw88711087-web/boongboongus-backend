package com.boongboongus.backend.domain.job.service;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.dto.JobResponse.Kind;
import com.boongboongus.backend.domain.job.repository.JobRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {
    private static final Duration JOB_TTL = Duration.ofHours(24);
    private final JobRepository jobs;
    private final IdempotentWrite idempotency;

    JobService(JobRepository jobs, IdempotentWrite idempotency) {
        this.jobs = jobs;
        this.idempotency = idempotency;
    }

    public JobResponse enqueue(String userId, Kind kind) {
        String id = "job_" + UUID.randomUUID();
        Instant now = now();
        jobs.insert(id, userId, kind, now, now.plus(JOB_TTL));
        return get(userId, id);
    }

    public JobResponse get(String userId, String id) {
        JobResponse job = owned(userId, id, false);
        if (!job.expiresAt().isAfter(now())) {
            throw new Rejected(HttpStatus.GONE, "JOB_EXPIRED", "만료된 작업입니다.");
        }
        return job;
    }

    public JobResponse cancel(String userId, String id, String key) {
        return idempotency.run(userId, key, List.of("cancel-ai-job", id), JobResponse.class, () -> {
            JobResponse job = owned(userId, id, true);
            if (!job.expiresAt().isAfter(now())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return switch (job.status()) {
                case SUCCEEDED, FAILED -> throw new Rejected(
                        HttpStatus.CONFLICT, "JOB_ALREADY_FINISHED", "이미 완료된 작업입니다.");
                case CANCEL_REQUESTED, CANCELLED -> job;
                case QUEUED, RUNNING -> {
                    jobs.requestCancellation(id);
                    yield owned(userId, id, false);
                }
            };
        });
    }

    public boolean start(String id) {
        return jobs.start(id, now());
    }

    // Save result data in the same transaction and commit it only if this transition succeeds.
    public boolean succeed(String id, String resourceId) {
        if (Objects.requireNonNull(resourceId).isBlank()) {
            throw new IllegalArgumentException("A completed job requires a resource ID");
        }
        return jobs.finish(id, now(), resourceId, null);
    }

    public boolean fail(String id, ErrorDetail error) {
        return jobs.finish(id, now(), null, Objects.requireNonNull(error));
    }

    @Scheduled(fixedDelay = 2000)
    void finalizeCancellations() {
        jobs.finalizeCancellations(now());
    }

    private JobResponse owned(String userId, String id, boolean lock) {
        return jobs.findOwned(id, userId, lock).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static final class Rejected extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        Rejected(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
