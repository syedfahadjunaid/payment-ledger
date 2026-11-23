package com.paymentledger.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe view of the {@code ledger.*} section in application.yml.
 *
 * <p>{@code @Validated} makes the app refuse to start if a secret is missing or too short,
 * which is much better than failing on the first payment in production.
 */
@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String adminToken,
        @NotEmpty List<@Pattern(regexp = "[A-Z]{3}") String> supportedCurrencies,
        @Positive long maxAmountMinor,
        @Valid @NotNull Provider provider,
        @Valid @NotNull Saga saga,
        @Valid @NotNull Reconciliation reconciliation,
        @Valid @NotNull Idempotency idempotency) {

    /** The card payment provider: where to call it, and the secret its webhooks are signed with. */
    public record Provider(
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String webhookSecret,
            @NotNull Duration webhookTolerance,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {}

    /** When the recovery job steps in for a saga that stopped half-way. */
    public record Saga(
            @NotNull Duration recoveryInterval,
            @NotNull Duration resubmitAfter,
            @NotNull Duration pollAfter) {}

    public record Reconciliation(@NotBlank String cron, @NotBlank String zone) {}

    public record Idempotency(@NotNull Duration retention) {}
}
