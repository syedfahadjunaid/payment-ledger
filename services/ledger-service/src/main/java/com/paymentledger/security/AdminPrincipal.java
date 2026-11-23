package com.paymentledger.security;

/** An operator using the admin token (creates merchants, runs reconciliation). */
public enum AdminPrincipal implements Actor {
    INSTANCE;

    @Override
    public String actorName() {
        return "admin";
    }
}
