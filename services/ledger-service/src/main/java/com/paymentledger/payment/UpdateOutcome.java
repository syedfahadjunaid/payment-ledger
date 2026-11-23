package com.paymentledger.payment;

import java.util.UUID;

/** What applying a {@link ProviderUpdate} did. Stored with each webhook event for investigations. */
public record UpdateOutcome(Result result, String detail, UUID merchantId) {

    public enum Result {
        APPLIED,
        IGNORED,
        CONFLICT,
        UNKNOWN_REFERENCE,
        UNSUPPORTED
    }

    static UpdateOutcome of(Transition transition, UUID merchantId) {
        Result result = switch (transition.kind()) {
            case MOVE -> Result.APPLIED;
            case IGNORE -> Result.IGNORED;
            case CONFLICT -> Result.CONFLICT;
        };
        return new UpdateOutcome(result, transition.reason(), merchantId);
    }

    public static UpdateOutcome unknownReference(String detail) {
        return new UpdateOutcome(Result.UNKNOWN_REFERENCE, detail, null);
    }

    public static UpdateOutcome unsupported(String detail) {
        return new UpdateOutcome(Result.UNSUPPORTED, detail, null);
    }

    static UpdateOutcome conflict(String detail, UUID merchantId) {
        return new UpdateOutcome(Result.CONFLICT, detail, merchantId);
    }
}
