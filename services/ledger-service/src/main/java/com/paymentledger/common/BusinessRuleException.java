package com.paymentledger.common;

/**
 * The request is well-formed, but a business rule says no (HTTP 422), e.g. insufficient funds.
 *
 * <p>{@code code} is a stable, machine-readable reason ("insufficient_funds") that clients can
 * branch on; the message is for humans and may change.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final String field;

    public BusinessRuleException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    /** The request field the rule is about, or null. */
    public String getField() {
        return field;
    }
}
