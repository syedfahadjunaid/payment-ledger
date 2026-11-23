package com.paymentledger.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The result of one reconciliation run: which checks ran, what they found, and the balance sheet
 * per merchant and currency. Stored as JSON in {@code reconciliation_runs} and never changed.
 */
public record ReconciliationReport(
        UUID id,
        LocalDate businessDate,
        Status status,
        String triggeredBy,
        Instant startedAt,
        Instant finishedAt,
        List<CheckResult> checks,
        List<CurrencyPosition> positions,
        List<Finding> findings) {

    public enum Status {
        /** Every check passed: the books balance and agree with the provider. */
        BALANCED,
        /** At least one finding. Someone must investigate. */
        DISCREPANCIES,
        /** A check couldn't run (e.g. the provider report was unavailable). */
        INCOMPLETE
    }

    /** @param findings how many problems this check found (0 = passed) */
    public record CheckResult(String name, String description, int findings) {}

    /**
     * One discrepancy.
     *
     * @param merchantId null when it can't be attributed (e.g. a provider charge we have never heard of)
     * @param reference  the id involved: account, transaction, payment, refund or provider id
     */
    public record Finding(String check, UUID merchantId, String reference, String message) {}

    /**
     * The balance sheet for one merchant and currency, in minor units:
     * assets (cash at the provider) must equal liabilities (wallets + refunds in transit).
     */
    public record CurrencyPosition(UUID merchantId, String currency, long providerClearingMinor, long walletsMinor,
                                   long refundsInTransitMinor, boolean balanced) {}
}
