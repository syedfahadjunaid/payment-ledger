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

/**
 * An account in the ledger. Every column is {@code updatable = false}: Hibernate never writes
 * an UPDATE for an account. The balance is changed only by a database trigger when entries are
 * inserted, so it can't drift from the entries through a code path that forgot to update it.
 *
 * <p>Gotcha: after posting entries, the {@code balanceMinor} of an account object already loaded
 * in this transaction is stale (the database changed it behind Hibernate's back). Read balances
 * with {@link LedgerService#balanceOf}, which always asks the database.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, updatable = false)
    private NormalBalance normalBalance;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    /** Maintained by the database (trigger); the insert uses the column default 0. */
    @Column(name = "balance_minor", nullable = false, insertable = false, updatable = false)
    private long balanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {} // for JPA

    LedgerAccount(UUID merchantId, AccountType type, String currency, Instant createdAt) {
        this.merchantId = merchantId;
        this.type = type;
        this.currency = currency;
        this.normalBalance = type.normalBalance();
        this.allowNegative = type.allowNegative();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public NormalBalance getNormalBalance() { return normalBalance; }
    public boolean isAllowNegative() { return allowNegative; }
    public long getBalanceMinor() { return balanceMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
