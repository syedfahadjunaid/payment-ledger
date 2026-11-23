package com.paymentledger.reconciliation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation every night for the business day that just ended. Several instances of
 * the service may fire at the same time; the advisory lock in {@link LedgerChecks} lets one run.
 */
@Component
public class ReconciliationScheduler {

    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final ZoneId zone;

    public ReconciliationScheduler(ReconciliationService reconciliation, Clock clock,
                                   @Value("${ledger.reconciliation.zone}") String zone) {
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${ledger.reconciliation.cron}", zone = "${ledger.reconciliation.zone}")
    public void reconcileYesterday() {
        reconciliation.run(LocalDate.now(clock.withZone(zone)).minusDays(1), "system:scheduler");
    }
}
