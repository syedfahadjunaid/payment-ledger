package com.paymentledger.common;

import com.paymentledger.provider.InvalidWebhookSignatureException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 "problem+json" responses.
 *
 * <p>Status codes are deliberate: 400 malformed request, 401 no/invalid credentials, 403 valid
 * credentials but not for this endpoint, 404 not found <em>or not yours</em>, 409 the current
 * state forbids it (retrying later may work), 422 a business rule says no (with a {@code code}).
 *
 * <p>Security rule: never send stack traces, SQL or internal hostnames to the client.
 * Log the details server-side and return a short, generic message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One entry per invalid request field, so a client can show the message next to the input. */
    public record InvalidField(String field, String message) {}

    /** Adds {@code "errors": [{"field": "amountMinor", "message": "must be greater than 0"}]}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Some fields are invalid.");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    ProblemDetail invalidSignature(InvalidWebhookSignatureException e) {
        log.warn("Rejected webhook: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setProperty("code", e.getCode());
        if (e.getField() != null) {
            problem.setProperty("errors", List.of(new InvalidField(e.getField(), e.getMessage())));
        }
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.getCode());
        return problem;
    }

    /**
     * Optimistic-lock failure (@Version), lock timeout or deadlock. Nothing was written (the
     * transaction rolled back), so the client can safely retry with the same Idempotency-Key.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail concurrentUpdate(ConcurrencyFailureException e) {
        log.info("Concurrency failure: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Another request changed the same data at the same time. Retry with the same Idempotency-Key.");
        problem.setProperty("code", "concurrent_update");
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(DataIntegrityViolationException e) {
        log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data.");
    }
}
