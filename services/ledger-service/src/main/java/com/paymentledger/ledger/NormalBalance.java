package com.paymentledger.ledger;

/**
 * The side of the ledger that increases an account.
 *
 * <p>Entries store signed amounts: positive = debit, negative = credit, and each transaction's
 * entries sum to zero. What a debit <em>means</em> depends on the account:
 * <ul>
 *   <li>{@code DEBIT}-normal (assets, e.g. cash at the provider): a debit increases the balance;</li>
 *   <li>{@code CREDIT}-normal (liabilities, e.g. a customer's wallet: money we owe them): a credit
 *       increases the balance.</li>
 * </ul>
 * So a top-up debits "provider clearing" (+, we hold more cash) and credits the wallet (-, we owe
 * the customer more), and both balances go up. That is the whole trick of double entry.
 */
public enum NormalBalance {
    DEBIT,
    CREDIT;

    /** How a signed entry amount changes a balance kept in this direction. */
    public long balanceChange(long signedEntryAmount) {
        return this == DEBIT ? signedEntryAmount : Math.negateExact(signedEntryAmount);
    }
}
