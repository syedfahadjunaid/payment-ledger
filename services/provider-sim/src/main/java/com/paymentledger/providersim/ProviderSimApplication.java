package com.paymentledger.providersim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A stand-in for a card payment provider (think Stripe or Adyen), so the ledger can be run and
 * tested end to end without a real account. It behaves like a real provider in the ways that
 * matter for correctness:
 * <ul>
 *   <li>charges and refunds are asynchronous: "processing" first, the outcome later by webhook;</li>
 *   <li>webhooks are HMAC-signed, retried with backoff until acknowledged (at-least-once delivery);</li>
 *   <li>the {@code pm_card_chaos} payment method delivers duplicates and stale events out of order;</li>
 *   <li>POSTs are idempotent by {@code Idempotency-Key};</li>
 *   <li>a daily settlement report, the input to reconciliation.</li>
 * </ul>
 * State is in memory only: restart it and its settlement reports forget earlier charges, which
 * reconciliation will then (correctly) report as discrepancies.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProviderSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderSimApplication.class, args);
    }
}
