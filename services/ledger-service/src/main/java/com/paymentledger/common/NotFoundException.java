package com.paymentledger.common;

/**
 * The resource doesn't exist, <em>or belongs to another merchant</em> (HTTP 404).
 * Answering 403 for someone else's wallet would confirm that the id exists.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
