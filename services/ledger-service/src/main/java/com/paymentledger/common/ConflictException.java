package com.paymentledger.common;

/** The current state of a resource forbids this action (HTTP 409), e.g. refunding a failed payment. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
