package com.paymentledger.payment;

import java.util.concurrent.Executor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a task after the current database transaction has committed, never before and never if
 * it rolls back.
 *
 * <p>Why: calling the payment provider <em>inside</em> the transaction would (a) hold database
 * locks during a slow network call, and (b) risk charging the card for a payment row that then
 * rolls back. So the saga commits its local step first, and only then makes the remote call.
 */
final class AfterCommit {

    private AfterCommit() {}

    static void execute(Executor executor, Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("AfterCommit needs an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(task);
            }
        });
    }
}
