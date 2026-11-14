package com.boongboongus.backend.domain.couple.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.couple.dto.CoupleInviteResponse;
import com.boongboongus.backend.domain.couple.service.CoupleInviteService;
import com.boongboongus.backend.domain.couple.service.CoupleConnectService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CoupleInviteController {
    private final CoupleInviteService invites;

    CoupleInviteController(CoupleInviteService invites) {
        this.invites = invites;
    }

    @GetMapping("/api/v1/couple-invites/me")
    @Operation(operationId = "getMyCoupleInvite", summary = "내 초대 코드 조회")
    ResponseEntity<?> getCurrent(Principal principal) {
        return invites.findCurrent(principal.getName())
                .<ResponseEntity<?>>map(invite -> ResponseEntity.ok().header("Cache-Control", "no-store")
                        .body(ApiResponse.success(invite)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ErrorResponse.of("INVITE_NOT_FOUND", "유효한 초대 코드가 없습니다.", List.of())));
    }

    @PostMapping("/api/v1/couple-invites")
    @Operation(operationId = "createCoupleInvite", summary = "내 초대 코드 발급")
    ResponseEntity<?> create(Principal principal, @RequestHeader("Idempotency-Key") String key) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "BAD_REQUEST", "Idempotency-Key는 16자 이상 128자 이하여야 합니다.", List.of()));
        }
        ApiResponse<CoupleInviteResponse> response = ApiResponse.success(invites.createOrGet(principal.getName(), key));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(response);
    }

    @DeleteMapping("/api/v1/couple-invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeInvite", summary = "내 초대 코드 폐기")
    void revoke(Principal principal, @PathVariable String inviteId) {
        if (inviteId.isBlank() || inviteId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        invites.revoke(principal.getName(), inviteId);
    }

    @ExceptionHandler(CoupleConnectService.Rejected.class)
    ResponseEntity<ErrorResponse> rejected(CoupleConnectService.Rejected exception) {
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoupleInviteService.AlreadyCoupled.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse alreadyCoupled() {
        return ErrorResponse.of("ALREADY_COUPLED", "이미 연결된 커플이 있습니다.", List.of());
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse keyReused() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 키입니다.", List.of());
    }
}
