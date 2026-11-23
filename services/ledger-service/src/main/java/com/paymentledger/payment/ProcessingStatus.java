package com.paymentledger.payment;

/**
 * Lifecycle of anything we ask the payment provider to do: a top-up charge or a refund.
 *
 * <pre>
 *   CREATED --accepted/processing--> PROCESSING --succeeded--> SUCCEEDED
 *      |                                  \-------failed-----> FAILED
 *      \--succeeded / failed (the webhook overtook the API response)--^
 * </pre>
 *
 * <p>All transition rules live in {@link #on}, a {@code switch} over every status and event.
 * The compiler rejects the switch if a case is missing, so adding a status or event forces you
 * to decide what happens in every situation.
 *
 * <p>Webhooks can arrive twice, late, or in the wrong order. The rule that keeps money correct:
 * <b>only move forward</b>. A final status never changes; anything that would move it backwards
 * is ignored, and anything that contradicts it is flagged as a conflict.
 */
public enum ProcessingStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public Transition on(ProviderEvent event) {
        return switch (this) {
            case CREATED -> switch (event) {
                case ACCEPTED, PROCESSING -> Transition.moveTo(PROCESSING);
                // The provider's webhook can arrive before its own HTTP response reached us.
                case SUCCEEDED -> Transition.moveTo(SUCCEEDED);
                case FAILED -> Transition.moveTo(FAILED);
            };
            case PROCESSING -> switch (event) {
                case ACCEPTED, PROCESSING -> Transition.ignore("nothing new: already PROCESSING");
                case SUCCEEDED -> Transition.moveTo(SUCCEEDED);
                case FAILED -> Transition.moveTo(FAILED);
            };
            case SUCCEEDED -> switch (event) {
                case ACCEPTED, PROCESSING -> Transition.ignore("stale event: already SUCCEEDED");
                case SUCCEEDED -> Transition.ignore("duplicate: already SUCCEEDED");
                case FAILED -> Transition.conflict("provider reports FAILED, but we already recorded SUCCEEDED");
            };
            case FAILED -> switch (event) {
                case ACCEPTED, PROCESSING -> Transition.ignore("stale event: already FAILED");
                case FAILED -> Transition.ignore("duplicate: already FAILED");
                case SUCCEEDED -> Transition.conflict("provider reports SUCCEEDED, but we already recorded FAILED");
            };
        };
    }
}
