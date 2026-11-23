package com.paymentledger.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The payment provider's wire format. {@code ignoreUnknown}: providers add fields over time,
 * and a new field must not break our integration.
 */
public final class ProviderDtos {

    private ProviderDtos() {}

    /** {@code reference} is our payment id, echoed back in every webhook about this charge. */
    public record CreateChargeRequest(long amountMinor, String currency, String paymentMethod, String reference) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChargeResponse(String id, String reference, String status, long amountMinor, String currency,
                                 String failureReason) {}

    /** {@code reference} is our refund id. */
    public record CreateRefundRequest(String chargeId, long amountMinor, String reference) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(String id, String chargeId, String reference, String status, long amountMinor,
                                 String currency, String failureReason) {}

    /** The provider's statement for one day: what it says it settled. Input to reconciliation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettlementReport(LocalDate date, List<SettledItem> charges, List<SettledItem> refunds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettledItem(String id, String reference, long amountMinor, String currency, Instant settledAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderError(String error, String message) {}

    /**
     * A webhook. {@code data} describes the charge or refund <em>as it is now</em>; we act on its
     * {@code status}, and use {@code type} only to tell charges from refunds.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookEvent(String id, String type, Instant createdAt, WebhookObject data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookObject(String objectType, String id, String chargeId, String reference, String status,
                                long amountMinor, String currency, String failureReason) {}
}
