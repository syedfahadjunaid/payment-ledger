package com.paymentledger.payment;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.Amounts;
import com.paymentledger.common.BusinessRuleException;
import com.paymentledger.common.ConflictException;
import com.paymentledger.common.Money;
import com.paymentledger.common.NotFoundException;
import com.paymentledger.ledger.AccountType;
import com.paymentledger.ledger.InsufficientFundsException;
import com.paymentledger.ledger.LedgerAccount;
import com.paymentledger.ledger.LedgerService;
import com.paymentledger.ledger.Leg;
import com.paymentledger.ledger.PostingRequest;
import com.paymentledger.ledger.TransactionType;
import com.paymentledger.payment.PaymentDtos.PaymentView;
import com.paymentledger.payment.PaymentDtos.RefundRequest;
import com.paymentledger.payment.PaymentDtos.RefundView;
import com.paymentledger.payment.PaymentDtos.TopUpRequest;
import com.paymentledger.wallet.Wallet;
import com.paymentledger.wallet.WalletRepository;
import com.paymentledger.wallet.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 1 of both sagas: the part that runs inside the merchant's HTTP request (and its
 * idempotency transaction). The provider is called only after this commits.
 */
@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final WalletService walletService;
    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final PaymentSaga paymentSaga;
    private final RefundSaga refundSaga;
    private final AuditService audit;
    private final Amounts amounts;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, RefundRepository refunds, WalletService walletService,
                          WalletRepository wallets, LedgerService ledger, PaymentSaga paymentSaga, RefundSaga refundSaga,
                          AuditService audit, Amounts amounts, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.walletService = walletService;
        this.wallets = wallets;
        this.ledger = ledger;
        this.paymentSaga = paymentSaga;
        this.refundSaga = refundSaga;
        this.audit = audit;
        this.amounts = amounts;
        this.clock = clock;
    }

    /** Records the top-up (CREATED). No money moves until the provider confirms the charge. */
    @Transactional
    public PaymentView startTopUp(UUID merchantId, UUID walletId, TopUpRequest request) {
        Wallet wallet = walletService.require(merchantId, walletId);
        if (!wallet.getCurrency().equals(request.currency())) {
            throw new BusinessRuleException("currency_mismatch", "this wallet holds " + wallet.getCurrency(), "currency");
        }
        amounts.requireWithinLimit(request.amountMinor(), request.currency(), "amountMinor");

        Payment payment = payments.save(new Payment(merchantId, walletId, request.currency(), request.amountMinor(),
                request.paymentMethod(), clock.instant()));
        audit.record(merchantId, "TOP_UP_REQUESTED", "payment", payment.getId(),
                Money.describe(payment.getAmountMinor(), payment.getCurrency()) + " to wallet " + walletId);
        paymentSaga.submitAfterCommit(payment.getId());
        return PaymentView.of(payment, List.of());
    }

    /**
     * Reserves the refund in the ledger (the money leaves the wallet now) and records it CREATED.
     *
     * <p>The payment row is locked first. Two refunds of one payment therefore run one at a time,
     * and the second sees the first's {@code refundedMinor}: together they can't exceed the payment.
     */
    @Transactional
    public RefundView startRefund(UUID merchantId, UUID paymentId, RefundRequest request) {
        Payment payment = payments.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("payment " + paymentId + " not found"));
        if (payment.getStatus() != ProcessingStatus.SUCCEEDED) {
            throw new ConflictException("payment_not_refundable",
                    "only a SUCCEEDED payment can be refunded; this one is " + payment.getStatus());
        }
        long amount = request.amountMinor();
        if (amount > payment.refundableMinor()) {
            throw new BusinessRuleException("refund_exceeds_payment", "at most "
                    + Money.describe(payment.refundableMinor(), payment.getCurrency()) + " can still be refunded", "amountMinor");
        }

        Instant now = clock.instant();
        payment.reserveRefund(amount, now);
        Refund refund = refunds.save(new Refund(payment, amount, request.reason(), now));

        Wallet wallet = wallets.findById(payment.getWalletId()).orElseThrow();
        LedgerAccount inTransit = ledger.systemAccount(merchantId, AccountType.REFUNDS_IN_TRANSIT, payment.getCurrency());
        try {
            ledger.post(new PostingRequest(merchantId, TransactionType.REFUND_RESERVE, refund.getId(),
                    payment.getCurrency(), "Refund of payment " + paymentId + " reserved",
                    List.of(Leg.debit(wallet.getLedgerAccountId(), amount), Leg.credit(inTransit.getId(), amount))));
        } catch (InsufficientFundsException e) {
            throw new InsufficientFundsException(e.getAccountId(),
                    "the wallet no longer holds enough to refund this amount (the customer spent it): " + e.getMessage());
        }
        audit.record(merchantId, "REFUND_REQUESTED", "refund", refund.getId(),
                Money.describe(amount, payment.getCurrency()) + " of payment " + paymentId);
        refundSaga.submitAfterCommit(refund.getId());
        return RefundView.of(refund);
    }

    public PaymentView getPayment(UUID merchantId, UUID paymentId) {
        Payment payment = payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new NotFoundException("payment " + paymentId + " not found"));
        return PaymentView.of(payment, refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId));
    }

    public RefundView getRefund(UUID merchantId, UUID refundId) {
        return refunds.findByIdAndMerchantId(refundId, merchantId)
                .map(RefundView::of)
                .orElseThrow(() -> new NotFoundException("refund " + refundId + " not found"));
    }
}
