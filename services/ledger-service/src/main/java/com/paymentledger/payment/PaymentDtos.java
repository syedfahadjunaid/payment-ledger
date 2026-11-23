package com.paymentledger.payment;

import com.paymentledger.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    /**
     * Top up a wallet by card. {@code paymentMethod} is a token from the provider's checkout
     * (we never see card numbers). The simulator knows: pm_card_ok, pm_card_declined,
     * pm_card_invalid, pm_card_chaos (duplicate and out-of-order webhooks), pm_card_refund_fails.
     */
    public record TopUpRequest(
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
            @NotBlank @Pattern(regexp = "pm_[a-z0-9_]{1,60}", message = "must be a payment method token like pm_card_ok")
            String paymentMethod) {}

    public record RefundRequest(@NotNull @Positive Long amountMinor, @Size(max = 200) String reason) {}

    public record PaymentView(UUID id, UUID walletId, Money amount, Money refunded, String paymentMethod,
                              ProcessingStatus status, String providerChargeId, String failureReason,
                              Instant createdAt, Instant succeededAt, List<RefundView> refunds) {

        static PaymentView of(Payment p, List<Refund> refunds) {
            return new PaymentView(p.getId(), p.getWalletId(), Money.of(p.getAmountMinor(), p.getCurrency()),
                    Money.of(p.getRefundedMinor(), p.getCurrency()), p.getPaymentMethod(), p.getStatus(),
                    p.getProviderChargeId(), p.getFailureReason(), p.getCreatedAt(), p.getSucceededAt(),
                    refunds.stream().map(RefundView::of).toList());
        }
    }

    public record RefundView(UUID id, UUID paymentId, Money amount, ProcessingStatus status, String reason,
                             String providerRefundId, String failureReason, Instant createdAt, Instant completedAt) {

        static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getPaymentId(), Money.of(r.getAmountMinor(), r.getCurrency()),
                    r.getStatus(), r.getReason(), r.getProviderRefundId(), r.getFailureReason(), r.getCreatedAt(),
                    r.getCompletedAt());
        }
    }
}
