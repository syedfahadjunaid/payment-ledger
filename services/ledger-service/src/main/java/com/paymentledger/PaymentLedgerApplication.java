package com.paymentledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Wallets, transfers and refunds on a double-entry ledger.
 *
 * <p>Package map (each package is one feature; start reading at {@code ledger}):
 * <ul>
 *   <li>{@code ledger}: accounts, transactions and entries; the only code that moves money</li>
 *   <li>{@code wallet}, {@code transfer}, {@code payment}: what merchants call through the API</li>
 *   <li>{@code provider}: the card payment provider (HTTP client, signed webhooks)</li>
 *   <li>{@code idempotency}, {@code audit}, {@code reconciliation}: making money operations safe to
 *       retry, traceable, and provably correct</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentLedgerApplication.class, args);
    }
}
