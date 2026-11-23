package com.paymentledger.ledger;

/**
 * The chart of accounts. Each merchant has one wallet account per wallet, and one of each system
 * account per currency, created on first use.
 *
 * <pre>
 *   Assets (DEBIT-normal)               = Liabilities (CREDIT-normal)
 *   PROVIDER_CLEARING                   = sum of WALLET + REFUNDS_IN_TRANSIT
 *   cash the provider holds for us        what we owe customers
 * </pre>
 * The reconciliation job checks that equation for every merchant and currency.
 */
public enum AccountType {

    /** A customer's balance. We owe it to them, so it's a liability. Can never go negative. */
    WALLET(NormalBalance.CREDIT, false),

    /** Money the payment provider has collected for us (card top-ups minus refunds paid out). */
    PROVIDER_CLEARING(NormalBalance.DEBIT, false),

    /** Refunds taken out of a wallet but not yet confirmed by the provider (a saga's pending step). */
    REFUNDS_IN_TRANSIT(NormalBalance.CREDIT, false);

    private final NormalBalance normalBalance;
    private final boolean allowNegative;

    AccountType(NormalBalance normalBalance, boolean allowNegative) {
        this.normalBalance = normalBalance;
        this.allowNegative = allowNegative;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }

    public boolean allowNegative() {
        return allowNegative;
    }
}
