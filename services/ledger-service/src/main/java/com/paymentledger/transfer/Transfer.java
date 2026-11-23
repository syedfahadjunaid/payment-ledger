package com.paymentledger.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A completed wallet-to-wallet transfer. Append-only (a DB trigger forbids UPDATE and DELETE).
 *
 * <p>Unlike most entities here the id is chosen in Java, because the ledger transaction must
 * reference this transfer before the transfer row (which references the ledger transaction)
 * can be inserted. Such entities are saved with {@code EntityManager.persist}: Spring Data's
 * {@code save} would see a non-null id, assume the row exists, and run a pointless SELECT first.
 */
@Entity
@Immutable
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "source_wallet_id", nullable = false)
    private UUID sourceWalletId;

    @Column(name = "destination_wallet_id", nullable = false)
    private UUID destinationWalletId;

    @Column(nullable = false)
    private String currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    private String description;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {} // for JPA

    Transfer(UUID id, UUID merchantId, UUID sourceWalletId, UUID destinationWalletId, String currency,
             long amountMinor, String description, UUID ledgerTransactionId, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.sourceWalletId = sourceWalletId;
        this.destinationWalletId = destinationWalletId;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.description = description;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getSourceWalletId() { return sourceWalletId; }
    public UUID getDestinationWalletId() { return destinationWalletId; }
    public String getCurrency() { return currency; }
    public long getAmountMinor() { return amountMinor; }
    public String getDescription() { return description; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
}
