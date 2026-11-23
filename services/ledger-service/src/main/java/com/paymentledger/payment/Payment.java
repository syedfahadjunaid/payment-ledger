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

/**
 * A card top-up of a wallet, processed by the payment provider. State changes only through
 * {@link #apply}, which follows the rules in {@link ProcessingStatus}.
 *
 * <p>Concurrency: callers lock the row ({@code SELECT ... FOR UPDATE}) before changing it, and
 * {@code @Version} adds optimistic locking as a second line of defence: if two transactions ever
 * changed it from the same starting version, the second UPDATE would fail instead of silently
 * overwriting the first.
 */
@Entity
@Table(name = "payments")
public class Payment {

    private static final int MAX_REASON = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    /** Refunds reserved or completed; the database CHECKs it never exceeds {@code amountMinor}. */
    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "payment_method", nullable = false, updatable = false)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingStatus status;

    @Column(name = "provider_charge_id")
    private String providerChargeId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Version
    private long version;

    protected Payment() {} // for JPA

    Payment(UUID merchantId, UUID walletId, String currency, long amountMinor, String paymentMethod, Instant now) {
        this.merchantId = merchantId;
        this.walletId = walletId;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.paymentMethod = paymentMethod;
        this.status = ProcessingStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies provider news. Returns the transition so the caller can do the side effects
     * (crediting the wallet when it moved to SUCCEEDED).
     */
    Transition apply(ProviderUpdate update, Instant now) {
        Transition transition = status.on(update.event());
        if (transition.kind() == Transition.Kind.CONFLICT) {
            return transition;
        }
        if (providerChargeId == null && update.providerId() != null) {
            providerChargeId = update.providerId(); // even a stale event can tell us the charge id
        }
        if (transition.kind() == Transition.Kind.MOVE) {
            status = transition.target();
            updatedAt = now;
            if (status == ProcessingStatus.SUCCEEDED) {
                succeededAt = now;
            } else if (status == ProcessingStatus.FAILED) {
                failureReason = truncate(update.failureReason());
            }
        }
        return transition;
    }

    long refundableMinor() {
        return amountMinor - refundedMinor;
    }

    void reserveRefund(long amount, Instant now) {
        if (status != ProcessingStatus.SUCCEEDED || amount <= 0 || amount > refundableMinor()) {
            throw new IllegalStateException("refund of " + amount + " not allowed on payment " + id);
        }
        refundedMinor = Math.addExact(refundedMinor, amount);
        updatedAt = now;
    }

    /** Compensation: a refund failed, so its amount may be refunded again. */
    void releaseRefund(long amount, Instant now) {
        if (amount <= 0 || amount > refundedMinor) {
            throw new IllegalStateException("cannot release " + amount + " on payment " + id);
        }
        refundedMinor -= amount;
        updatedAt = now;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "failed";
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getWalletId() { return walletId; }
    public String getCurrency() { return currency; }
    public long getAmountMinor() { return amountMinor; }
    public long getRefundedMinor() { return refundedMinor; }
    public String getPaymentMethod() { return paymentMethod; }
    public ProcessingStatus getStatus() { return status; }
    public String getProviderChargeId() { return providerChargeId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSucceededAt() { return succeededAt; }
}
