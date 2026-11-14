package com.boongboongus.backend.domain.auth.service;

import org.springframework.http.HttpStatus;

public final class TokenExchangeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private TokenExchangeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static TokenExchangeException verificationFailed() {
        return new TokenExchangeException(HttpStatus.UNAUTHORIZED, "OAUTH_VERIFICATION_FAILED",
                "카카오 로그인 인증을 확인할 수 없습니다.");
    }

    static TokenExchangeException refreshExpired() {
        return new TokenExchangeException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED",
                "로그인이 만료되었습니다. 다시 로그인해 주세요.");
    }

    static TokenExchangeException refreshReused() {
        return new TokenExchangeException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                "로그인 세션이 폐기되었습니다. 다시 로그인해 주세요.");
    }

    static TokenExchangeException reauthMismatch() {
        return new TokenExchangeException(HttpStatus.FORBIDDEN, "REAUTH_SUBJECT_MISMATCH",
                "현재 계정과 카카오 계정이 일치하지 않습니다.");
    }

    static TokenExchangeException reauthDenied() {
        return new TokenExchangeException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                "재인증 요청을 확인할 수 없습니다.");
    }

    static TokenExchangeException reauthRequired() {
        return new TokenExchangeException(HttpStatus.UNAUTHORIZED, "REAUTH_REQUIRED",
                "회원 탈퇴를 위해 본인 재인증이 필요합니다.");
    }

    static TokenExchangeException activeDriveExists() {
        return new TokenExchangeException(HttpStatus.CONFLICT, "ACTIVE_DRIVE_EXISTS",
                "진행 중인 주행을 종료하거나 준비 중인 주행을 취소해 주세요.");
    }

    static TokenExchangeException unavailable() {
        return new TokenExchangeException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "잠시 후 다시 시도해 주세요.");
    }

    static TokenExchangeException notFound() {
        return new TokenExchangeException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "로그인 시도를 찾을 수 없습니다.");
    }

    static TokenExchangeException alreadyUsed() {
        return new TokenExchangeException(HttpStatus.CONFLICT, "AUTH_CODE_ALREADY_USED",
                "이미 사용한 로그인 시도입니다.");
    }
}
