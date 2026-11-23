package com.paymentledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** The header of one money movement; its {@link LedgerEntry} rows say which accounts changed. */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private String currency;

    /** The payment, refund or transfer this movement belongs to. */
    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransaction() {} // for JPA

    LedgerTransaction(PostingRequest request, Instant createdAt) {
        this.merchantId = request.merchantId();
        this.type = request.type();
        this.currency = request.currency();
        this.referenceId = request.referenceId();
        this.description = request.description();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public TransactionType getType() { return type; }
    public String getCurrency() { return currency; }
    public UUID getReferenceId() { return referenceId; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
