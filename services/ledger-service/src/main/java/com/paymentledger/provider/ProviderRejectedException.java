package com.paymentledger.provider;

/** The provider definitely refused (HTTP 4xx), e.g. an invalid card. The operation can be failed. */
public class ProviderRejectedException extends RuntimeException {

    public ProviderRejectedException(String reason) {
        super(reason);
    }
}
