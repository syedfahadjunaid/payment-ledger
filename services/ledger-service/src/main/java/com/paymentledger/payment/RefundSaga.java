package com.paymentledger.payment;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.Money;
import com.paymentledger.ledger.AccountType;
import com.paymentledger.ledger.LedgerAccount;
import com.paymentledger.ledger.LedgerService;
import com.paymentledger.ledger.Leg;
import com.paymentledger.ledger.PostingRequest;
import com.paymentledger.ledger.TransactionType;
import com.paymentledger.provider.PaymentProviderClient;
import com.paymentledger.provider.ProviderDtos.CreateRefundRequest;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.ProviderRejectedException;
import com.paymentledger.provider.ProviderUnavailableException;
import com.paymentledger.wallet.Wallet;
import com.paymentledger.wallet.WalletRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refund saga, with a compensating step.
 *
 * <pre>
 *  1. RefundService  [tx] reserve: wallet -> REFUNDS_IN_TRANSIT (REFUND_RESERVE)      -> commit
 *                         The customer can't spend money that is on its way back to the card.
 *  2. submit()            POST /v1/refunds, Idempotency-Key: refund-{id}  (no tx open)
 *  3a. succeeded     [tx] settle: REFUNDS_IN_TRANSIT -> PROVIDER_CLEARING (REFUND_SETTLE)
 *  3b. failed        [tx] compensate: REFUNDS_IN_TRANSIT -> wallet (REFUND_REVERSAL), and the
 *                         payment's refundable amount is released again
 * </pre>
 * Compensation never deletes or edits step 1: it adds a new transaction in the opposite direction,
 * so the ledger shows exactly what happened.
 *
 * <p>Lock order (always the same, so no deadlocks): payment row, then refund row, then ledger accounts.
 */
@Service
public class RefundSaga {

    private static final Logger log = LoggerFactory.getLogger(RefundSaga.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final WalletRepository wallets;
    private final PaymentProviderClient provider;
    private final LedgerService ledger;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final ExecutorService executor;
    private final Clock clock;

    public RefundSaga(RefundRepository refunds, PaymentRepository payments, WalletRepository wallets,
                      PaymentProviderClient provider, LedgerService ledger, AuditService audit,
                      TransactionTemplate transaction, ExecutorService sagaExecutor, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.wallets = wallets;
        this.provider = provider;
        this.ledger = ledger;
        this.audit = audit;
        this.transaction = transaction;
        this.executor = sagaExecutor;
        this.clock = clock;
    }

    void submitAfterCommit(UUID refundId) {
        AfterCommit.execute(executor, () -> submit(refundId));
    }

    /** Step 2. Idempotent, like {@link PaymentSaga#submit}; no transaction open during the HTTP call. */
    public void submit(UUID refundId) {
        Refund refund = refunds.findById(refundId).orElseThrow();
        if (refund.getStatus() != ProcessingStatus.CREATED) {
            return;
        }
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        ProviderUpdate update;
        try {
            RefundResponse response = provider.createRefund(new CreateRefundRequest(payment.getProviderChargeId(),
                    refund.getAmountMinor(), refundId.toString()), "refund-" + refundId);
            update = new ProviderUpdate(response.id(), refundId, ProviderEvent.ACCEPTED, response.amountMinor(),
                    response.currency(), null);
        } catch (ProviderRejectedException e) {
            update = new ProviderUpdate(null, refundId, ProviderEvent.FAILED, refund.getAmountMinor(),
                    refund.getCurrency(), e.getMessage());
        } catch (ProviderUnavailableException e) {
            log.warn("Provider unavailable for refund {}; will retry: {}", refundId, e.getMessage());
            return;
        }
        ProviderUpdate result = update;
        transaction.executeWithoutResult(status -> apply(result));
    }

    /** Step 3: provider news about a refund, from any channel. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UpdateOutcome apply(ProviderUpdate update) {
        Optional<UUID> paymentId = refunds.findPaymentIdById(update.reference());
        if (paymentId.isEmpty()) {
            return UpdateOutcome.unknownReference("no refund " + update.reference());
        }
        Payment payment = payments.findByIdForUpdate(paymentId.get()).orElseThrow();   // lock 1
        Refund refund = refunds.findByIdForUpdate(update.reference()).orElseThrow();     // lock 2

        String conflict = contradiction(refund, update);
        if (conflict != null) {
            audit.recordAs(PaymentSaga.ACTOR, refund.getMerchantId(), "REFUND_PROVIDER_CONFLICT", "refund",
                    refund.getId(), conflict);
            return UpdateOutcome.conflict(conflict, refund.getMerchantId());
        }

        Transition transition = refund.apply(update, clock.instant());
        switch (transition.kind()) {
            case MOVE -> {
                if (transition.target() == ProcessingStatus.SUCCEEDED) {
                    settle(refund);
                } else if (transition.target() == ProcessingStatus.FAILED) {
                    compensate(refund, payment);
                }
            }
            case IGNORE -> log.debug("Refund {}: {}", refund.getId(), transition.reason());
            case CONFLICT -> audit.recordAs(PaymentSaga.ACTOR, refund.getMerchantId(), "REFUND_PROVIDER_CONFLICT",
                    "refund", refund.getId(), transition.reason());
        }
        return UpdateOutcome.of(transition, refund.getMerchantId());
    }

    private static String contradiction(Refund refund, ProviderUpdate update) {
        if (update.providerId() != null && refund.getProviderRefundId() != null
                && !refund.getProviderRefundId().equals(update.providerId())) {
            return "refund " + update.providerId() + " does not match " + refund.getProviderRefundId();
        }
        if (update.amountMinor() != refund.getAmountMinor() || !refund.getCurrency().equals(update.currency())) {
            return "provider reports " + Money.describe(update.amountMinor(), String.valueOf(update.currency()))
                    + " but the refund is " + Money.describe(refund.getAmountMinor(), refund.getCurrency());
        }
        return null;
    }

    /** 3a: the money really left; move it from "in transit" out of our provider balance. */
    private void settle(Refund refund) {
        LedgerAccount inTransit = ledger.systemAccount(refund.getMerchantId(), AccountType.REFUNDS_IN_TRANSIT,
                refund.getCurrency());
        LedgerAccount clearing = ledger.systemAccount(refund.getMerchantId(), AccountType.PROVIDER_CLEARING,
                refund.getCurrency());
        ledger.post(new PostingRequest(refund.getMerchantId(), TransactionType.REFUND_SETTLE, refund.getId(),
                refund.getCurrency(), "Refund paid out, provider refund " + refund.getProviderRefundId(),
                List.of(Leg.debit(inTransit.getId(), refund.getAmountMinor()),
                        Leg.credit(clearing.getId(), refund.getAmountMinor()))));
        audit.recordAs(PaymentSaga.ACTOR, refund.getMerchantId(), "REFUND_SUCCEEDED", "refund", refund.getId(),
                Money.describe(refund.getAmountMinor(), refund.getCurrency()));
    }

    /** 3b, the compensating transaction: give the reserved money back to the wallet. */
    private void compensate(Refund refund, Payment payment) {
        Wallet wallet = wallets.findById(payment.getWalletId()).orElseThrow();
        LedgerAccount inTransit = ledger.systemAccount(refund.getMerchantId(), AccountType.REFUNDS_IN_TRANSIT,
                refund.getCurrency());
        ledger.post(new PostingRequest(refund.getMerchantId(), TransactionType.REFUND_REVERSAL, refund.getId(),
                refund.getCurrency(), "Refund failed (" + refund.getFailureReason() + "), reservation reversed",
                List.of(Leg.debit(inTransit.getId(), refund.getAmountMinor()),
                        Leg.credit(wallet.getLedgerAccountId(), refund.getAmountMinor()))));
        payment.releaseRefund(refund.getAmountMinor(), clock.instant());
        audit.recordAs(PaymentSaga.ACTOR, refund.getMerchantId(), "REFUND_FAILED", "refund", refund.getId(),
                refund.getFailureReason() + "; " + Money.describe(refund.getAmountMinor(), refund.getCurrency())
                        + " returned to wallet " + wallet.getId());
    }
}
