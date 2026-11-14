package com.boongboongus.backend.domain.couple.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.couple.dto.CoupleConnectRequest;
import com.boongboongus.backend.domain.couple.dto.CoupleMascotNameRequest;
import com.boongboongus.backend.domain.couple.dto.CoupleStartDateRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import com.boongboongus.backend.domain.couple.service.CoupleConnectService;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse;
import com.boongboongus.backend.domain.couple.service.CoupleService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CoupleController {
    private final CoupleService couples;

    private final CoupleConnectService connections;

    private final DrivePreparationService drives;

    CoupleController(CoupleService couples, CoupleConnectService connections, DrivePreparationService drives) {
        this.couples = couples;
        this.connections = connections;
        this.drives = drives;
    }

    @PatchMapping("/api/v1/couples/me")
    @Operation(operationId = "updateCoupleStartDate", summary = "커플 시작일 변경")
    ResponseEntity<?> updateStartDate(Principal principal, @RequestHeader("X-Couple-Id") String coupleId,
            @Valid @RequestBody CoupleStartDateRequest request) {
        if (invalidCoupleId(coupleId)) {
            return invalidCoupleIdResponse();
        }
        return ResponseEntity.ok(ApiResponse.success(couples.updateStartDate(principal.getName(), coupleId,
                request.startDate(), request.expectedVersion())));
    }

    @PatchMapping("/api/v1/couples/me/mascot-name")
    @Operation(operationId = "updateCoupleMascotName", summary = "붕붕이 이름 변경")
    ResponseEntity<?> updateMascotName(Principal principal, @RequestHeader("X-Couple-Id") String coupleId,
            @Valid @RequestBody CoupleMascotNameRequest request) {
        if (invalidCoupleId(coupleId)) {
            return invalidCoupleIdResponse();
        }
        return ResponseEntity.ok(ApiResponse.success(couples.updateMascotName(principal.getName(), coupleId,
                request.name(), request.expectedVersion())));
    }

    @DeleteMapping("/api/v1/couples/me")
    @Operation(operationId = "disconnectCouple", summary = "현재 커플 연결 해제")
    ResponseEntity<?> disconnect(Principal principal, @RequestHeader("X-Couple-Id") String coupleId) {
        if (invalidCoupleId(coupleId)) {
            return invalidCoupleIdResponse();
        }
        couples.disconnect(principal.getName(), coupleId, drives);
        return ResponseEntity.noContent().build();
    }

    private static boolean invalidCoupleId(String coupleId) {
        return coupleId.isBlank() || coupleId.length() > 80;
    }

    private static ResponseEntity<ErrorResponse> invalidCoupleIdResponse() {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "BAD_REQUEST", "커플 ID를 확인해 주세요.", List.of()));
    }

    @PostMapping("/api/v1/couples")
    @Operation(operationId = "connectCouple", summary = "초대 코드로 커플 연결")
    ResponseEntity<?> connect(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CoupleConnectRequest request) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "BAD_REQUEST", "Idempotency-Key는 16자 이상 128자 이하여야 합니다.", List.of()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(connections.connect(principal.getName(), key, request.code())));
    }

    @PostMapping("/api/v1/couples/restore")
    @Operation(operationId = "restoreCoupleConnection", summary = "이전 커플 연결 복구")
    ResponseEntity<?> restore(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CoupleConnectRequest request) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "BAD_REQUEST", "Idempotency-Key는 16자 이상 128자 이하여야 합니다.", List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(connections.restore(principal.getName(), key, request.code())));
    }

    @ExceptionHandler(CoupleConnectService.Rejected.class)
    ResponseEntity<ErrorResponse> rejected(CoupleConnectService.Rejected exception) {
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    ResponseEntity<ErrorResponse> keyReused() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 키입니다.", List.of()));
    }

    @GetMapping("/api/v1/couples/me")
    @Operation(summary = "내 커플 연결 상태 조회")
    ApiResponse<CurrentCoupleResponse> getCurrent(Principal principal) {
        return ApiResponse.success(couples.getCurrent(principal.getName()));
    }
}
