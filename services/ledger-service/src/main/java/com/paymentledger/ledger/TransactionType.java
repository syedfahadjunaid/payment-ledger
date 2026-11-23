package com.paymentledger.ledger;

/**
 * Every kind of money movement. Each has a fixed shape of entries:
 *
 * <pre>
 *   TOP_UP           debit PROVIDER_CLEARING    credit WALLET              (card payment succeeded)
 *   TRANSFER         debit WALLET (source)      credit WALLET (target)
 *   REFUND_RESERVE   debit WALLET               credit REFUNDS_IN_TRANSIT  (refund requested)
 *   REFUND_SETTLE    debit REFUNDS_IN_TRANSIT   credit PROVIDER_CLEARING   (provider paid the card)
 *   REFUND_REVERSAL  debit REFUNDS_IN_TRANSIT   credit WALLET              (provider refused: compensate)
 * </pre>
 * Mistakes are never edited away: a reversal is a new transaction in the opposite direction.
 */
public enum TransactionType {
    TOP_UP,
    TRANSFER,
    REFUND_RESERVE,
    REFUND_SETTLE,
    REFUND_REVERSAL
}
