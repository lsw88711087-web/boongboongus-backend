package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.repository.ReauthProofRepository;
import com.boongboongus.backend.domain.drive.ActiveDriveLookup;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AccountDeletionService {
    private final ReauthProofRepository proofs;
    private final ActiveDriveLookup drives;

    AccountDeletionService(ReauthProofRepository proofs, ActiveDriveLookup drives) {
        this.proofs = proofs;
        this.drives = drives;
    }

    public void delete(String userId, String proof) {
        if (proof == null || proof.isBlank()
                || !proofs.isValidForDeletion(ServiceTokens.refreshHash(proof), userId, Instant.now())) {
            throw TokenExchangeException.reauthRequired();
        }
        if (drives.existsForMember(userId)) {
            throw TokenExchangeException.activeDriveExists();
        }
        // Enable deletion only after provider unlinking and local cleanup can complete together.
        throw TokenExchangeException.unavailable();
    }
}
