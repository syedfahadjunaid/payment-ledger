package com.paymentledger.ledger;

import com.paymentledger.common.BusinessRuleException;
import java.util.UUID;

/** Posting would take an account that can't go negative (a wallet) below zero. HTTP 422. */
public class InsufficientFundsException extends BusinessRuleException {

    private final UUID accountId;

    public InsufficientFundsException(UUID accountId, String message) {
        super("insufficient_funds", message, "amountMinor");
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}
