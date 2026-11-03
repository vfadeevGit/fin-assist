package ru.stnovator.finassist.service;

import java.util.UUID;

/** Localized business validation failure; listeners redact details when the offending row is hidden. */
public class ContractTermsException extends RuntimeException {
    private final UUID addendumId;

    public ContractTermsException(String message) {
        this(message, null);
    }

    public ContractTermsException(String message, UUID addendumId) {
        super(message);
        this.addendumId = addendumId;
    }

    public UUID getAddendumId() {
        return addendumId;
    }
}
