package com.paymentledger.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A refund of (part of) a {@link Payment} back to the card. Same lifecycle as a payment. */
@Entity
@Table(name = "refunds")
public class Refund {

    private static final int MAX_REASON = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingStatus status;

    @Column(updatable = false)
    private String reason;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected Refund() {} // for JPA

    Refund(Payment payment, long amountMinor, String reason, Instant now) {
        this.merchantId = payment.getMerchantId();
        this.paymentId = payment.getId();
        this.currency = payment.getCurrency();
        this.amountMinor = amountMinor;
        this.reason = reason;
        this.status = ProcessingStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    Transition apply(ProviderUpdate update, Instant now) {
        Transition transition = status.on(update.event());
        if (transition.kind() == Transition.Kind.CONFLICT) {
            return transition;
        }
        if (providerRefundId == null && update.providerId() != null) {
            providerRefundId = update.providerId();
        }
        if (transition.kind() == Transition.Kind.MOVE) {
            status = transition.target();
            updatedAt = now;
            if (status.isFinal()) {
                completedAt = now;
            }
            if (status == ProcessingStatus.FAILED) {
                String why = update.failureReason() == null ? "failed" : update.failureReason();
                failureReason = why.length() <= MAX_REASON ? why : why.substring(0, MAX_REASON);
            }
        }
        return transition;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getPaymentId() { return paymentId; }
    public String getCurrency() { return currency; }
    public long getAmountMinor() { return amountMinor; }
    public ProcessingStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getProviderRefundId() { return providerRefundId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
