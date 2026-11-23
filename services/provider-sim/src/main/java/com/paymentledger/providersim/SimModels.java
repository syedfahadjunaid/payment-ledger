package com.paymentledger.providersim;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The simulator's wire format: request bodies, resources, webhook events and reports. */
public final class SimModels {

    private SimModels() {}

    public record CreateChargeRequest(Long amountMinor, String currency, String paymentMethod, String reference) {}

    public record CreateRefundRequest(String chargeId, Long amountMinor, String reference) {}

    /** Immutable: the store replaces the whole record when the status changes. */
    public record Charge(String id, String reference, long amountMinor, String currency, String paymentMethod,
                         String status, long refundedMinor, String failureReason, Instant createdAt, Instant settledAt) {

        Charge settled(String newStatus, String reason, Instant at) {
            return new Charge(id, reference, amountMinor, currency, paymentMethod, newStatus, refundedMinor, reason,
                    createdAt, at);
        }

        Charge withRefunded(long newRefundedMinor) {
            return new Charge(id, reference, amountMinor, currency, paymentMethod, status, newRefundedMinor,
                    failureReason, createdAt, settledAt);
        }
    }

    public record Refund(String id, String chargeId, String reference, long amountMinor, String currency,
                         String status, String failureReason, Instant createdAt, Instant settledAt) {

        Refund settled(String newStatus, String reason, Instant at) {
            return new Refund(id, chargeId, reference, amountMinor, currency, newStatus, reason, createdAt, at);
        }
    }

    /** A webhook. {@code data} is a snapshot of the charge or refund when the event happened. */
    public record Event(String id, String type, Instant createdAt, EventData data) {}

    public record EventData(String objectType, String id, String chargeId, String reference, String status,
                            long amountMinor, String currency, String failureReason) {

        static EventData of(Charge c) {
            return new EventData("charge", c.id(), null, c.reference(), c.status(), c.amountMinor(), c.currency(),
                    c.failureReason());
        }

        static EventData of(Refund r) {
            return new EventData("refund", r.id(), r.chargeId(), r.reference(), r.status(), r.amountMinor(),
                    r.currency(), r.failureReason());
        }
    }

    public record SettledItem(String id, String reference, long amountMinor, String currency, Instant settledAt) {}

    public record SettlementReport(LocalDate date, List<SettledItem> charges, List<SettledItem> refunds) {}
}
