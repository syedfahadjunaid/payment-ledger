package com.paymentledger.common;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * How the API shows an amount: integer minor units, the currency, and a human-readable string.
 *
 * <p>Why minor units in a {@code long}? Binary floating point can't represent 0.10 exactly
 * (0.1 + 0.2 != 0.3), and rounding errors in money are bugs. Cents (or yen, which have no
 * minor unit) are whole numbers, so integer arithmetic is exact. {@link Math#addExact} turns
 * an overflow into an exception instead of a silently wrong balance.
 *
 * @param amountMinor e.g. 1250 for 12.50 EUR, or 1250 for 1250 JPY
 * @param formatted   e.g. "12.50" (EUR) or "1250" (JPY); for display only, never parse it back
 */
public record Money(long amountMinor, String currency, String formatted) {

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency, format(amountMinor, currency));
    }

    /** "12.50" for 1250 EUR: uses the number of decimals ISO 4217 defines for the currency. */
    public static String format(long amountMinor, String currency) {
        int decimals = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return BigDecimal.valueOf(amountMinor, decimals).toPlainString();
    }

    /** "12.50 EUR", for messages and audit details. */
    public static String describe(long amountMinor, String currency) {
        return format(amountMinor, currency) + " " + currency;
    }
}
