package com.boongboongus.backend.domain.auth.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.auth.dto.OnboardingRequest;
import com.boongboongus.backend.domain.auth.dto.ProfilePatch;
import com.boongboongus.backend.domain.auth.dto.TokenResponse.User;
import com.boongboongus.backend.domain.auth.service.AccountService.InvalidProfile;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.boongboongus.backend.domain.auth.service.AccountService;
import com.boongboongus.backend.domain.auth.service.AccountDeletionService;
import com.boongboongus.backend.domain.auth.service.TokenExchangeException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {
    private final AccountService accounts;

    private final AccountDeletionService deletions;

    MeController(AccountService accounts, AccountDeletionService deletions) {
        this.accounts = accounts;
        this.deletions = deletions;
    }

    @DeleteMapping("/api/v1/me")
    @Operation(operationId = "deleteMe", summary = "회원 탈퇴 조건 확인")
    ResponseEntity<?> delete(Principal principal,
            @RequestHeader(value = "X-Reauth-Token", required = false) String proof) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .body(ErrorResponse.of("AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", List.of()));
        }
        deletions.delete(principal.getName(), proof);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TokenExchangeException.class)
    ResponseEntity<ErrorResponse> deletionError(TokenExchangeException exception) {
        var error = new ErrorResponse(new ErrorResponse.ErrorDetail(exception.code(), exception.getMessage(),
                List.of(), exception.status() == HttpStatus.SERVICE_UNAVAILABLE), ApiResponse.Meta.current());
        return ResponseEntity.status(exception.status()).body(error);
    }

    @GetMapping("/api/v1/me")
    @Operation(operationId = "getMe", summary = "내 계정 조회")
    ResponseEntity<?> getMe(Principal principal) {
        return respond(principal, accounts::get);
    }

    @PutMapping("/api/v1/me/onboarding")
    @Operation(operationId = "completeOnboarding", summary = "가입 프로필과 필수 동의 완료")
    ResponseEntity<?> onboard(Principal principal, @Valid @RequestBody OnboardingRequest request) {
        return respond(principal, id -> accounts.onboard(id, request));
    }

    @PatchMapping("/api/v1/me")
    @Operation(operationId = "updateMe", summary = "내 프로필 수정")
    ResponseEntity<?> patch(Principal principal, @RequestBody ProfilePatch request) {
        return respond(principal, id -> accounts.patch(id, request));
    }

    @ExceptionHandler(InvalidProfile.class)
    ResponseEntity<ErrorResponse> invalidProfile(InvalidProfile exception) {
        return ResponseEntity.unprocessableContent()
                .body(ErrorResponse.of(exception.code(), "프로필과 약관 동의를 확인해 주세요.", List.of()));
    }

    private ResponseEntity<?> respond(Principal principal, java.util.function.Function<String, User> action) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .body(ErrorResponse.of("AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", List.of()));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(action.apply(principal.getName())));
    }
}
