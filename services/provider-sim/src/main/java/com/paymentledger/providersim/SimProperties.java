package com.paymentledger.providersim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sim")
public record SimProperties(
        @NotBlank String apiKey,
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String webhookSecret,
        @NotBlank String webhookUrl,
        @NotNull Duration settleDelay,
        @Positive int maxDeliveryAttempts) {}
