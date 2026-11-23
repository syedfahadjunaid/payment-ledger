package com.paymentledger.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A customer's stored-value wallet in one currency. The money itself lives in the ledger: the
 * wallet points to its own {@code WALLET} ledger account, whose balance is the wallet balance.
 */
@Entity
@Immutable
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** The merchant's own id for this customer. */
    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Column(nullable = false)
    private String currency;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Wallet() {} // for JPA

    public Wallet(UUID merchantId, String customerRef, String currency, UUID ledgerAccountId, Instant createdAt) {
        this.merchantId = merchantId;
        this.customerRef = customerRef;
        this.currency = currency;
        this.ledgerAccountId = ledgerAccountId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getCustomerRef() { return customerRef; }
    public String getCurrency() { return currency; }
    public UUID getLedgerAccountId() { return ledgerAccountId; }
    public Instant getCreatedAt() { return createdAt; }
}
