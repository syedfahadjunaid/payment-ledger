package com.paymentledger.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * One side of a posting. Build it with {@link #debit} or {@link #credit}, which take a positive
 * amount, so call sites read like an accounting textbook:
 * {@code List.of(Leg.debit(source, 500), Leg.credit(target, 500))}.
 *
 * @param amountMinor signed: positive = debit, negative = credit, never zero
 */
public record Leg(UUID accountId, long amountMinor) {

    public Leg {
        Objects.requireNonNull(accountId, "accountId");
        if (amountMinor == 0) {
            throw new IllegalArgumentException("a ledger leg can't be zero");
        }
    }

    public static Leg debit(UUID accountId, long amountMinor) {
        return new Leg(accountId, requirePositive(amountMinor));
    }

    public static Leg credit(UUID accountId, long amountMinor) {
        return new Leg(accountId, Math.negateExact(requirePositive(amountMinor)));
    }

    private static long requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amountMinor);
        }
        return amountMinor;
    }
}
