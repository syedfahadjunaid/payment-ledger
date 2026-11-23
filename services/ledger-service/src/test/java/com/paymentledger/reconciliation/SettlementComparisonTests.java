package com.paymentledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.provider.ProviderDtos.SettledItem;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import com.paymentledger.reconciliation.LedgerChecks.OurItem;
import com.paymentledger.reconciliation.LedgerChecks.Snapshot;
import com.paymentledger.reconciliation.ReconciliationReport.Finding;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** External reconciliation is a pure function: easy to test every kind of mismatch. */
class SettlementComparisonTests {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
    private final UUID merchant = UUID.randomUUID();

    @Test
    void matchingRecordsHaveNoFindings() {
        OurItem ours = payment("ch_1", "SUCCEEDED", 1000);
        List<Finding> findings = compare(List.of(settled("ch_1", 1000)), List.of(ours), Map.of("ch_1", ours));

        assertThat(findings).isEmpty();
    }

    @Test
    void reportsEveryKindOfMismatch() {
        OurItem wrongAmount = payment("ch_amount", "SUCCEEDED", 1000);
        OurItem notSucceeded = payment("ch_pending", "PROCESSING", 500);
        OurItem notSettled = payment("ch_ours_only", "SUCCEEDED", 700);

        List<Finding> findings = compare(
                List.of(settled("ch_amount", 999), settled("ch_pending", 500), settled("ch_theirs_only", 300)),
                List.of(wrongAmount, notSettled),
                Map.of("ch_amount", wrongAmount, "ch_pending", notSucceeded));

        assertThat(findings).extracting(Finding::message).satisfiesExactlyInAnyOrder(
                m -> assertThat(m).contains("ch_amount").contains("999"),
                m -> assertThat(m).contains("ch_pending").contains("PROCESSING"),
                m -> assertThat(m).contains("ch_theirs_only").contains("no record"),
                m -> assertThat(m).contains("ch_ours_only").contains("did not settle"));
        assertThat(findings).filteredOn(f -> f.merchantId() == null).hasSize(1); // the unknown charge
    }

    private List<Finding> compare(List<SettledItem> theirs, List<OurItem> oursOnDate, Map<String, OurItem> oursById) {
        Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of(), oursOnDate, oursById, List.of(), Map.of());
        return SettlementComparison.compare(new SettlementReport(DAY, theirs, List.of()), snapshot);
    }

    private OurItem payment(String chargeId, String status, long amount) {
        return new OurItem(merchant, UUID.randomUUID(), chargeId, status, amount, "EUR");
    }

    private static SettledItem settled(String chargeId, long amount) {
        return new SettledItem(chargeId, UUID.randomUUID().toString(), amount, "EUR", Instant.parse("2026-11-22T10:00:00Z"));
    }
}
