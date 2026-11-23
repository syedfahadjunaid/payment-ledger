package com.paymentledger.payment;

import com.paymentledger.config.LedgerProperties;
import com.paymentledger.provider.PaymentProviderClient;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.ProviderRejectedException;
import com.paymentledger.provider.ProviderUnavailableException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes sagas that stopped half-way. Crashes, deploys and network blips happen; correctness
 * must not depend on every step running exactly once in one go.
 *
 * <ul>
 *   <li><b>Stuck in CREATED</b>: the provider call never happened or timed out. Call again with
 *       the same idempotency key; the provider returns the existing charge if it got the first one.</li>
 *   <li><b>Stuck in PROCESSING</b>: maybe a webhook was lost. Ask the provider for the current
 *       status and apply it through the same code path as a webhook.</li>
 * </ul>
 */
@Component
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentSaga paymentSaga;
    private final RefundSaga refundSaga;
    private final PaymentProviderClient provider;
    private final TransactionTemplate transaction;
    private final LedgerProperties.Saga settings;
    private final Clock clock;

    public SagaRecoveryJob(PaymentRepository payments, RefundRepository refunds, PaymentSaga paymentSaga,
                           RefundSaga refundSaga, PaymentProviderClient provider, TransactionTemplate transaction,
                           LedgerProperties props, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.paymentSaga = paymentSaga;
        this.refundSaga = refundSaga;
        this.provider = provider;
        this.transaction = transaction;
        this.settings = props.saga();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ledger.saga.recovery-interval}",
               initialDelayString = "${ledger.saga.recovery-interval}")
    public void run() {
        resubmitCreated();
        pollProcessing();
    }

    /** @return how many payments and refunds were re-submitted */
    public int resubmitCreated() {
        Instant cutoff = clock.instant().minus(settings.resubmitAfter());
        int count = 0;
        for (Payment payment : payments.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ProcessingStatus.CREATED, cutoff)) {
            log.info("Re-submitting payment {} to the provider", payment.getId());
            paymentSaga.submit(payment.getId());
            count++;
        }
        for (Refund refund : refunds.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ProcessingStatus.CREATED, cutoff)) {
            log.info("Re-submitting refund {} to the provider", refund.getId());
            refundSaga.submit(refund.getId());
            count++;
        }
        return count;
    }

    /** @return how many payments and refunds were checked with the provider */
    public int pollProcessing() {
        Instant cutoff = clock.instant().minus(settings.pollAfter());
        int count = 0;
        for (Payment payment : payments.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ProcessingStatus.PROCESSING, cutoff)) {
            try {
                ChargeResponse charge = provider.getCharge(payment.getProviderChargeId());
                ProviderEvent.fromProviderStatus(charge.status()).ifPresent(event -> transaction.executeWithoutResult(
                        status -> paymentSaga.apply(new ProviderUpdate(charge.id(), payment.getId(), event,
                                charge.amountMinor(), charge.currency(), charge.failureReason()))));
                count++;
            } catch (ProviderUnavailableException | ProviderRejectedException e) {
                log.warn("Could not check payment {} with the provider: {}", payment.getId(), e.getMessage());
            }
        }
        for (Refund refund : refunds.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ProcessingStatus.PROCESSING, cutoff)) {
            try {
                RefundResponse response = provider.getRefund(refund.getProviderRefundId());
                ProviderEvent.fromProviderStatus(response.status()).ifPresent(event -> transaction.executeWithoutResult(
                        status -> refundSaga.apply(new ProviderUpdate(response.id(), refund.getId(), event,
                                response.amountMinor(), response.currency(), response.failureReason()))));
                count++;
            } catch (ProviderUnavailableException | ProviderRejectedException e) {
                log.warn("Could not check refund {} with the provider: {}", refund.getId(), e.getMessage());
            }
        }
        return count;
    }
}
