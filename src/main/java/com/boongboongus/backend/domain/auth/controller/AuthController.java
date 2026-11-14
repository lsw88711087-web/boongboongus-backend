package com.boongboongus.backend.domain.auth.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.dto.LogoutRequest;
import com.boongboongus.backend.domain.auth.dto.RefreshTokenRequest;
import com.boongboongus.backend.domain.auth.dto.RefreshTokenResponse;
import com.boongboongus.backend.domain.auth.dto.TokenRequest;
import com.boongboongus.backend.domain.auth.service.AuthLogoutService;
import com.boongboongus.backend.domain.auth.service.AuthRefreshService;
import com.boongboongus.backend.domain.auth.dto.TokenResponse;
import com.boongboongus.backend.domain.auth.service.AuthTokenService;
import com.boongboongus.backend.domain.auth.service.TokenExchangeException;
import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.boongboongus.backend.domain.auth.dto.AuthorizationRequest;
import com.boongboongus.backend.domain.auth.dto.AuthorizationResponse;
import com.boongboongus.backend.domain.auth.service.AuthAuthorizationService;
import com.boongboongus.backend.domain.auth.service.AuthAuthorizationService.AuthorizationException;
import com.boongboongus.backend.domain.auth.dto.ReauthRequest;
import com.boongboongus.backend.domain.auth.dto.ReauthResponse;
import com.boongboongus.backend.domain.auth.service.AuthReauthService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@EnableConfigurationProperties(KakaoLoginProperties.class)
class AuthController {
    private final AuthAuthorizationService authorizations;

    private final AuthTokenService tokens;

    private final AuthRefreshService refreshes;
    private final AuthLogoutService logouts;

    private final AuthReauthService reauth;

    AuthController(AuthAuthorizationService authorizations, AuthTokenService tokens, AuthRefreshService refreshes,
            AuthLogoutService logouts, AuthReauthService reauth) {
        this.reauth = reauth;
        this.authorizations = authorizations;
        this.tokens = tokens;
        this.refreshes = refreshes;
        this.logouts = logouts;
    }

    @PostMapping("/reauth/authorizations")
    @Operation(operationId = "startReauthentication", summary = "카카오 본인 재인증 시작")
    ResponseEntity<?> startReauth(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReauthRequest request) {
        if (invalidKey(key)) {
            return invalidKeyResponse();
        }
        ApiResponse<AuthorizationResponse> response = ApiResponse.success(reauth.start(principal.getName(), key, request));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(response);
    }

    @PostMapping("/reauth")
    @Operation(operationId = "reauthenticate", summary = "카카오 본인 재인증 완료")
    ResponseEntity<?> completeReauth(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TokenRequest request) {
        if (invalidKey(key)) {
            return invalidKeyResponse();
        }
        ApiResponse<ReauthResponse> response = ApiResponse.success(reauth.complete(principal.getName(), key, request));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "현재 기기 로그아웃")
    ResponseEntity<?> logout(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LogoutRequest request) {
        if (invalidKey(key)) {
            return invalidKeyResponse();
        }
        logouts.logout(key, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshToken", summary = "액세스 토큰 갱신")
    ResponseEntity<?> refreshToken(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RefreshTokenRequest request) {
        if (invalidKey(key)) {
            return invalidKeyResponse();
        }
        ApiResponse<RefreshTokenResponse> response = ApiResponse.success(refreshes.refresh(key, request.refreshToken()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    @PostMapping("/token")
    @Operation(operationId = "exchangeSocialToken", summary = "카카오 인증 코드 교환")
    ResponseEntity<?> exchangeToken(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TokenRequest request) {
        if (invalidKey(key)) {
            return invalidKeyResponse();
        }
        ApiResponse<TokenResponse> response = ApiResponse.success(tokens.exchange(key, request));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    private static boolean invalidKey(String key) {
        return key.isBlank() || key.length() < 16 || key.length() > 128;
    }

    private static ResponseEntity<ErrorResponse> invalidKeyResponse() {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "BAD_REQUEST", "Idempotency-Key는 16자 이상 128자 이하여야 합니다.", List.of()));
    }

    @ExceptionHandler(TokenExchangeException.class)
    ResponseEntity<ErrorResponse> handleTokenExchange(TokenExchangeException exception) {
        if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity.status(exception.status()).body(handleDatabaseUnavailable());
        }
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleIdempotencyConflict() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "이미 다른 요청에 사용된 키입니다.", List.of());
    }

    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "startSocialAuthorization", summary = "카카오 로그인 시작")
    ApiResponse<AuthorizationResponse> startAuthorization(@Valid @RequestBody AuthorizationRequest request) {
        return ApiResponse.success(authorizations.startLogin(request));
    }

    @ExceptionHandler(AuthorizationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    ErrorResponse handleAuthorization(AuthorizationException exception) {
        return ErrorResponse.of(exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ErrorResponse handleDatabaseUnavailable() {
        return new ErrorResponse(new ErrorResponse.ErrorDetail(
                "SERVICE_UNAVAILABLE", "잠시 후 다시 시도해 주세요.", List.of(), true), ApiResponse.Meta.current());
    }
}
