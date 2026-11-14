package com.boongboongus.backend.domain.playlist.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.playlist.config.TrackCatalog;
import com.boongboongus.backend.domain.playlist.dto.PlaylistRequest;
import com.boongboongus.backend.domain.playlist.dto.PlaylistResponse;
import com.boongboongus.backend.domain.playlist.dto.PlaylistTracksPatch;
import com.boongboongus.backend.domain.playlist.service.PlaylistService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/playlists")
@EnableConfigurationProperties(TrackCatalog.class)
class PlaylistController {
    private final PlaylistService playlists;

    PlaylistController(PlaylistService playlists) {
        this.playlists = playlists;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "DJ 플레이리스트 추천 생성")
    ApiResponse<JobResponse> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PlaylistRequest request) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(playlists.request(principal.getName(), key, request));
    }

    @GetMapping("/{playlistId}")
    @Operation(summary = "추천 플레이리스트 상세 조회")
    ApiResponse<PlaylistResponse> get(Principal principal, @PathVariable String playlistId) {
        if (playlistId.isBlank() || playlistId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(playlists.get(principal.getName(), playlistId));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{playlistId}/tracks")
    @Operation(summary = "주행 참여자의 플레이리스트 곡 편집")
    ApiResponse<PlaylistResponse> editTracks(Principal principal, @PathVariable String playlistId,
            @Valid @RequestBody PlaylistTracksPatch request) {
        if (playlistId.isBlank() || playlistId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(playlists.editTracks(principal.getName(), playlistId, request));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse keyReused() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 키입니다.", List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    org.springframework.http.ResponseEntity<ErrorResponse> rejected(ResponseStatusException exception) {
        if (exception.getStatusCode() == HttpStatus.CONFLICT) {
            return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("VERSION_CONFLICT", "최신 플레이리스트를 조회해 주세요.", List.of()));
        }
        if ("INVALID_TRACK_SET".equals(exception.getReason())) {
            return org.springframework.http.ResponseEntity.unprocessableContent()
                    .body(ErrorResponse.of("INVALID_TRACK_SET", "기존 곡을 중복 없이 선택해 주세요.", List.of()));
        }
        if (exception.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
            return org.springframework.http.ResponseEntity.unprocessableContent()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "추천 조건을 확인해 주세요.", List.of()));
        }
        if (exception.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
            return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new ErrorResponse(new ErrorResponse.ErrorDetail("SERVICE_UNAVAILABLE",
                            "추천 곡 목록을 사용할 수 없습니다.", List.of(), true), ApiResponse.Meta.current()));
        }
        String code = exception.getStatusCode() == HttpStatus.NOT_FOUND ? "RESOURCE_NOT_FOUND" : "BAD_REQUEST";
        return org.springframework.http.ResponseEntity.status(exception.getStatusCode())
                .body(ErrorResponse.of(code, "요청을 처리할 수 없습니다.", List.of()));
    }
}
