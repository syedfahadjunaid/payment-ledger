package com.paymentledger.security;

/** Anyone who can be named in the audit trail: a merchant's API key, an operator, the system. */
public interface Actor {

    /** e.g. "api-key:sk_test_AbCd" or "admin". Never a secret. */
    String actorName();
}
