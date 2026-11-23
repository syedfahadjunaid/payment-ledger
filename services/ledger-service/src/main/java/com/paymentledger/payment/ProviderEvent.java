package com.paymentledger.payment;

import java.util.Optional;

/** What the payment provider told us about a charge or refund, from its API response or a webhook. */
public enum ProviderEvent {

    /** The provider's HTTP API accepted our request (it now has an id for it). */
    ACCEPTED,
    /** Still being processed (e.g. waiting for the card network). */
    PROCESSING,
    SUCCEEDED,
    FAILED;

    /** Maps the provider's status string ("succeeded") to an event; unknown strings are ignored. */
    public static Optional<ProviderEvent> fromProviderStatus(String status) {
        return switch (status == null ? "" : status) {
            case "processing" -> Optional.of(PROCESSING);
            case "succeeded" -> Optional.of(SUCCEEDED);
            case "failed" -> Optional.of(FAILED);
            default -> Optional.empty();
        };
    }
}
