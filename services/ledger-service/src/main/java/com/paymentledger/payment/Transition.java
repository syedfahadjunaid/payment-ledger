package com.paymentledger.payment;

/** The decision {@link ProcessingStatus#on} makes for one provider event. */
public record Transition(Kind kind, ProcessingStatus target, String reason) {

    public enum Kind {
        /** Move to {@code target}; the caller performs the side effects (ledger postings). */
        MOVE,
        /** Nothing to do: a duplicate or a stale event that arrived out of order. */
        IGNORE,
        /** The provider contradicts what we already recorded. No money moves; a human must look. */
        CONFLICT
    }

    public static Transition moveTo(ProcessingStatus target) {
        return new Transition(Kind.MOVE, target, "moved to " + target);
    }

    public static Transition ignore(String reason) {
        return new Transition(Kind.IGNORE, null, reason);
    }

    public static Transition conflict(String reason) {
        return new Transition(Kind.CONFLICT, null, reason);
    }
}
