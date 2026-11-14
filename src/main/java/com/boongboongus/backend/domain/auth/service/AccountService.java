package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.dto.OnboardingRequest;
import com.boongboongus.backend.domain.auth.dto.ProfilePatch;
import com.boongboongus.backend.domain.auth.dto.TokenResponse.User;
import com.boongboongus.backend.domain.legal.LegalDocumentService;
import com.boongboongus.backend.domain.legal.LegalDocumentResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.transaction.annotation.Transactional;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final AccountRepository accounts;

    private final LegalDocumentService legal;

    AccountService(AccountRepository accounts, LegalDocumentService legal) {
        this.accounts = accounts;
        this.legal = legal;
    }

    @Transactional
    public User onboard(String id, OnboardingRequest request) {
        String nickname = name(request.nickname());
        String mascot = request.mascotName() == null ? null : name(request.mascotName());
        validateDate(request.birthDate(), "INVALID_BIRTH_DATE");
        validateDate(request.coupleStartDate(), "VALIDATION_FAILED");

        var required = legal.listCurrent().stream().filter(LegalDocumentResponse::isRequired).toList();
        if (required.size() != 2 || request.consents().size() != 2 || required.stream().anyMatch(document ->
                request.consents().stream().noneMatch(consent -> consent.type() == document.type()
                        && consent.version().equals(document.version()) && Boolean.TRUE.equals(consent.agreed())))) {
            throw new InvalidProfile("REQUIRED_CONSENT_MISSING");
        }

        accounts.completeOnboarding(id, nickname, request.birthDate(), request.coupleStartDate(), mascot,
                request.gender() == null ? null : request.gender().name());
        required.forEach(document -> accounts.saveConsent(id, document.type().name(), document.version()));
        return get(id);
    }

    @Transactional
    public User patch(String id, ProfilePatch request) {
        if (!request.nicknamePresent() && !request.birthDatePresent()) {
            throw new InvalidProfile("VALIDATION_FAILED");
        }
        if (request.nicknamePresent()) {
            accounts.updateNickname(id, name(request.nickname()));
        }
        if (request.birthDatePresent()) {
            validateDate(request.birthDate(), "VALIDATION_FAILED");
            accounts.updateBirthDate(id, request.birthDate());
        }
        return get(id);
    }

    private static String name(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 20) {
            throw new InvalidProfile("VALIDATION_FAILED");
        }
        return value;
    }

    private static void validateDate(LocalDate date, String code) {
        if (date != null && date.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) {
            throw new InvalidProfile(code);
        }
    }

    public static final class InvalidProfile extends RuntimeException {
        private final String code;

        InvalidProfile(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public User get(String userId) {
        return accounts.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
