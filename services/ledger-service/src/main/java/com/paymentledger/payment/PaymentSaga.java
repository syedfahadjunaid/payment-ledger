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
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.CreateChargeRequest;
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
 * The top-up saga: a multi-step flow across two systems (our database and the provider) that
 * can't share one ACID transaction. Each step is a local transaction; the state in
 * {@code payments.status} records how far we got, so any step can be retried safely.
 *
 * <pre>
 *  1. TopUpService   [tx] payment CREATED (+ idempotency key)             -> commit
 *  2. submit()            POST /v1/charges, Idempotency-Key: payment-{id}  (no tx open)
 *                    [tx] CREATED -> PROCESSING, remember the charge id
 *  3. webhook        [tx] PROCESSING -> SUCCEEDED + TOP_UP ledger posting  (wallet credited)
 *                      or PROCESSING -> FAILED (no money moved, nothing to undo)
 * </pre>
 * If the process dies between steps, {@code SagaRecoveryJob} resumes it: step 2 is retried with
 * the same idempotency key (the provider returns the same charge), and a lost webhook is replaced
 * by asking the provider for the charge's status.
 */
@Service
public class PaymentSaga {

    private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);
    static final String ACTOR = "provider";

    private final PaymentRepository payments;
    private final WalletRepository wallets;
    private final PaymentProviderClient provider;
    private final LedgerService ledger;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final ExecutorService executor;
    private final Clock clock;

    public PaymentSaga(PaymentRepository payments, WalletRepository wallets, PaymentProviderClient provider,
                       LedgerService ledger, AuditService audit, TransactionTemplate transaction,
                       ExecutorService sagaExecutor, Clock clock) {
        this.payments = payments;
        this.wallets = wallets;
        this.provider = provider;
        this.ledger = ledger;
        this.audit = audit;
        this.transaction = transaction;
        this.executor = sagaExecutor;
        this.clock = clock;
    }

    /** Step 2 is scheduled for after step 1 commits; see {@link AfterCommit}. */
    void submitAfterCommit(UUID paymentId) {
        AfterCommit.execute(executor, () -> submit(paymentId));
    }

    /**
     * Step 2: ask the provider to charge the card. Safe to call any number of times: it does nothing
     * unless the payment is still CREATED, and the idempotency key makes the provider return the
     * same charge for a repeated request. Deliberately <em>not</em> {@code @Transactional}: no
     * database transaction may stay open during the HTTP call.
     */
    public void submit(UUID paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        if (payment.getStatus() != ProcessingStatus.CREATED) {
            return;
        }
        ProviderUpdate update;
        try {
            ChargeResponse charge = provider.createCharge(new CreateChargeRequest(payment.getAmountMinor(),
                    payment.getCurrency(), payment.getPaymentMethod(), payment.getId().toString()), "payment-" + paymentId);
            update = new ProviderUpdate(charge.id(), paymentId, ProviderEvent.ACCEPTED, charge.amountMinor(),
                    charge.currency(), null);
        } catch (ProviderRejectedException e) {
            // A definite "no": the card was never charged, so failing the payment is safe.
            update = new ProviderUpdate(null, paymentId, ProviderEvent.FAILED, payment.getAmountMinor(),
                    payment.getCurrency(), e.getMessage());
        } catch (ProviderUnavailableException e) {
            // Outcome unknown. Stay CREATED; the recovery job retries with the same idempotency key.
            log.warn("Provider unavailable for payment {}; will retry: {}", paymentId, e.getMessage());
            return;
        }
        ProviderUpdate result = update;
        transaction.executeWithoutResult(status -> apply(result));
    }

    /**
     * Step 3 (and the bookkeeping of step 2): apply provider news to the payment. Called from the
     * webhook handler, the saga itself, and the recovery job, always inside a transaction.
     *
     * <p>Locks the payment row first, so two webhooks for the same charge (say "succeeded" delivered
     * twice at once) run one after the other: the first credits the wallet, the second finds the
     * payment SUCCEEDED and is ignored. Exactly one TOP_UP posting, however often the news arrives.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UpdateOutcome apply(ProviderUpdate update) {
        Optional<Payment> found = payments.findByIdForUpdate(update.reference());
        if (found.isEmpty()) {
            return UpdateOutcome.unknownReference("no payment " + update.reference());
        }
        Payment payment = found.get();

        String conflict = contradiction(payment, update);
        if (conflict != null) {
            audit.recordAs(ACTOR, payment.getMerchantId(), "PAYMENT_PROVIDER_CONFLICT", "payment", payment.getId(), conflict);
            return UpdateOutcome.conflict(conflict, payment.getMerchantId());
        }

        Transition transition = payment.apply(update, clock.instant());
        switch (transition.kind()) {
            case MOVE -> {
                if (transition.target() == ProcessingStatus.SUCCEEDED) {
                    creditWallet(payment);
                } else if (transition.target() == ProcessingStatus.FAILED) {
                    audit.recordAs(ACTOR, payment.getMerchantId(), "PAYMENT_FAILED", "payment", payment.getId(),
                            payment.getFailureReason());
                }
            }
            case IGNORE -> log.debug("Payment {}: {}", payment.getId(), transition.reason());
            case CONFLICT -> audit.recordAs(ACTOR, payment.getMerchantId(), "PAYMENT_PROVIDER_CONFLICT", "payment",
                    payment.getId(), transition.reason());
        }
        return UpdateOutcome.of(transition, payment.getMerchantId());
    }

    /** The provider's facts must match ours; a different amount or charge is never "close enough". */
    private static String contradiction(Payment payment, ProviderUpdate update) {
        if (update.providerId() != null && payment.getProviderChargeId() != null
                && !payment.getProviderChargeId().equals(update.providerId())) {
            return "charge " + update.providerId() + " does not match " + payment.getProviderChargeId();
        }
        if (update.amountMinor() != payment.getAmountMinor() || !payment.getCurrency().equals(update.currency())) {
            return "provider reports " + Money.describe(update.amountMinor(), String.valueOf(update.currency()))
                    + " but the payment is " + Money.describe(payment.getAmountMinor(), payment.getCurrency());
        }
        return null;
    }

    /** The money movement: debit provider clearing (we hold more cash), credit the wallet (we owe more). */
    private void creditWallet(Payment payment) {
        Wallet wallet = wallets.findById(payment.getWalletId()).orElseThrow();
        LedgerAccount clearing = ledger.systemAccount(payment.getMerchantId(), AccountType.PROVIDER_CLEARING,
                payment.getCurrency());
        ledger.post(new PostingRequest(payment.getMerchantId(), TransactionType.TOP_UP, payment.getId(),
                payment.getCurrency(), "Card top-up, charge " + payment.getProviderChargeId(),
                List.of(Leg.debit(clearing.getId(), payment.getAmountMinor()),
                        Leg.credit(wallet.getLedgerAccountId(), payment.getAmountMinor()))));
        audit.recordAs(ACTOR, payment.getMerchantId(), "PAYMENT_SUCCEEDED", "payment", payment.getId(),
                Money.describe(payment.getAmountMinor(), payment.getCurrency()) + " credited to wallet " + wallet.getId());
    }
}
