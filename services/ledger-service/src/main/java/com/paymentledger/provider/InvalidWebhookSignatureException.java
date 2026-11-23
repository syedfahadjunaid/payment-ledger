package com.paymentledger.provider;

/** A webhook whose signature is missing, wrong or too old. HTTP 401; nothing is processed. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
