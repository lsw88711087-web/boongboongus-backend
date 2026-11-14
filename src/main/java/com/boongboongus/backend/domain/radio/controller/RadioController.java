package com.boongboongus.backend.domain.radio.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.radio.dto.RadioEpisode;
import com.boongboongus.backend.domain.radio.dto.RadioHistory;
import com.boongboongus.backend.domain.radio.dto.RadioRequest;
import com.boongboongus.backend.domain.radio.service.RadioService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/radio/episodes")
class RadioController {
    private final RadioService radio;

    RadioController(RadioService radio) {
        this.radio = radio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<JobResponse> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RadioRequest request) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(radio.create(principal.getName(), key, request));
    }

    @GetMapping
    ResponseEntity<ApiResponse<RadioHistory>> history(Principal principal, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        String origin = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(radio.history(principal.getName(), q, cursor, limit, origin)));
    }

    @GetMapping("/{episodeId}")
    ResponseEntity<ApiResponse<RadioEpisode>> get(Principal principal, @PathVariable String episodeId) {
        String origin = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(radio.get(principal.getName(), episodeId, origin)));
    }

    @GetMapping(value = "/{episodeId}/audio", produces = "audio/mpeg")
    ResponseEntity<byte[]> audio(Principal principal, @PathVariable String episodeId, @RequestParam long expires,
            @RequestParam String signature) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(radio.audio(principal.getName(), episodeId, expires, signature));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse keyReused() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "같은 키로 다른 요청을 보냈습니다.", List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> rejected(ResponseStatusException exception) {
        String code = switch (exception.getStatusCode().value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 422 -> "VALIDATION_FAILED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "BAD_REQUEST";
        };
        return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(new ErrorResponse.ErrorDetail(code,
                        "라디오 요청을 처리할 수 없습니다.", List.of(), exception.getStatusCode().is5xxServerError()),
                        ApiResponse.Meta.current()));
    }
}
