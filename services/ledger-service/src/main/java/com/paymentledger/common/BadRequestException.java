package com.paymentledger.common;

/** The request itself is malformed (HTTP 400), e.g. an Idempotency-Key with illegal characters. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
