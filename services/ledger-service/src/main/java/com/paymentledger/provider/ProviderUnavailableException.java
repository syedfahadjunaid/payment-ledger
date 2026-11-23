package com.paymentledger.provider;

/**
 * Timeout, connection error or 5xx: the outcome is <em>unknown</em>. The charge may have gone
 * through. Retry later with the same idempotency key; never treat this as a decline.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
