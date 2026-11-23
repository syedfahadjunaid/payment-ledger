package com.paymentledger.payment;

import com.paymentledger.idempotency.IdempotencyService;
import com.paymentledger.payment.PaymentDtos.PaymentView;
import com.paymentledger.payment.PaymentDtos.RefundRequest;
import com.paymentledger.payment.PaymentDtos.RefundView;
import com.paymentledger.payment.PaymentDtos.TopUpRequest;
import com.paymentledger.security.MerchantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Top-ups and refunds. Both answer <b>202 Accepted</b>: the request is safely recorded, and the
 * outcome arrives later from the provider. Poll {@code GET /api/payments/{id}} to follow it.
 * A replay (same Idempotency-Key) returns the original 202 response, header {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping("/wallets/{walletId}/top-ups")
    public ResponseEntity<PaymentView> topUp(@AuthenticationPrincipal MerchantPrincipal merchant,
                                             @PathVariable UUID walletId,
                                             @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                             @Valid @RequestBody TopUpRequest request,
                                             HttpServletRequest http) {
        return idempotency.execute(merchant.merchantId(), idempotencyKey, idempotency.fingerprint(http, request),
                        PaymentView.class, () -> payments.startTopUp(merchant.merchantId(), walletId, request))
                .toResponse(HttpStatus.ACCEPTED);
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentView getPayment(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId) {
        return payments.getPayment(merchant.merchantId(), paymentId);
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundView> refund(@AuthenticationPrincipal MerchantPrincipal merchant,
                                             @PathVariable UUID paymentId,
                                             @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                             @Valid @RequestBody RefundRequest request,
                                             HttpServletRequest http) {
        return idempotency.execute(merchant.merchantId(), idempotencyKey, idempotency.fingerprint(http, request),
                        RefundView.class, () -> payments.startRefund(merchant.merchantId(), paymentId, request))
                .toResponse(HttpStatus.ACCEPTED);
    }

    @GetMapping("/refunds/{refundId}")
    public RefundView getRefund(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID refundId) {
        return payments.getRefund(merchant.merchantId(), refundId);
    }
}
