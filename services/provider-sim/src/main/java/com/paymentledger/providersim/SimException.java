package com.paymentledger.providersim;

import org.springframework.http.HttpStatus;

/** An error answer in the provider's style: {@code {"error": "card_declined", "message": "..."}}. */
public class SimException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public SimException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
