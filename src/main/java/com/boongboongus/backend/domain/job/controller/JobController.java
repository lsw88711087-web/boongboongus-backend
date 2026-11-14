package com.boongboongus.backend.domain.job.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs")
class JobController {
    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{jobId}")
    @Operation(operationId = "getJob", summary = "AI 작업 상태 조회")
    ApiResponse<JobResponse> get(Principal principal, @PathVariable String jobId) {
        validateId(jobId);
        return ApiResponse.success(jobs.get(principal.getName(), jobId));
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(operationId = "cancelJob", summary = "AI 작업 취소 요청")
    ApiResponse<JobResponse> cancel(Principal principal, @PathVariable String jobId,
            @RequestHeader("Idempotency-Key") String key) {
        validateId(jobId);
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(jobs.cancel(principal.getName(), jobId, key));
    }

    @ExceptionHandler(JobService.Rejected.class)
    ResponseEntity<ErrorResponse> rejected(JobService.Rejected exception) {
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse keyReused() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 키입니다.", List.of());
    }

    private static void validateId(String jobId) {
        if (jobId.isBlank() || jobId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
