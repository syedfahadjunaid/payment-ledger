package com.paymentledger.reconciliation;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.NotFoundException;
import com.paymentledger.provider.PaymentProviderClient;
import com.paymentledger.provider.ProviderDtos.SettledItem;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import com.paymentledger.provider.ProviderRejectedException;
import com.paymentledger.provider.ProviderUnavailableException;
import com.paymentledger.reconciliation.LedgerChecks.Snapshot;
import com.paymentledger.reconciliation.ReconciliationReport.CheckResult;
import com.paymentledger.reconciliation.ReconciliationReport.Finding;
import com.paymentledger.reconciliation.ReconciliationReport.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daily reconciliation: proves the books balance, and that they agree with the provider.
 *
 * <ol>
 *   <li>Fetch the provider's settlement report for the day (outside any database transaction).</li>
 *   <li>Run the internal checks in one consistent snapshot ({@link LedgerChecks}).</li>
 *   <li>Compare our settled payments and refunds with the provider's ({@link SettlementComparison}).</li>
 *   <li>Store the report (append-only) and audit the run.</li>
 * </ol>
 * The job only reports; it never "fixes" money. Corrections are a human decision, made with new,
 * visible ledger transactions.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record RunSummary(UUID id, LocalDate businessDate, Status status, int findings, String triggeredBy,
                             Instant finishedAt) {}

    private final LedgerChecks checks;
    private final PaymentProviderClient provider;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReconciliationService(LedgerChecks checks, PaymentProviderClient provider, JdbcClient jdbc, JsonMapper json,
                                 AuditService audit, TransactionTemplate transaction, Clock clock) {
        this.checks = checks;
        this.provider = provider;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    public ReconciliationReport run(LocalDate businessDate, String triggeredBy) {
        Instant startedAt = clock.instant();

        SettlementReport settlement = null;
        String providerProblem = null;
        try {
            settlement = provider.settlementReport(businessDate);
        } catch (ProviderUnavailableException | ProviderRejectedException e) {
            providerProblem = e.getMessage();
        }

        List<String> chargeIds = settlement == null ? List.of() : ids(settlement.charges());
        List<String> refundIds = settlement == null ? List.of() : ids(settlement.refunds());
        Snapshot snapshot = checks.snapshot(businessDate, chargeIds, refundIds);

        List<CheckResult> checkResults = new ArrayList<>(snapshot.checks());
        List<Finding> findings = new ArrayList<>(snapshot.findings());
        if (settlement != null) {
            List<Finding> external = SettlementComparison.compare(settlement, snapshot);
            checkResults.add(new CheckResult(SettlementComparison.CHECK,
                    "our settled payments and refunds match the provider's settlement report for the day", external.size()));
            findings.addAll(external);
        } else {
            checkResults.add(new CheckResult(SettlementComparison.CHECK, "provider settlement report unavailable", 1));
            findings.add(new Finding(SettlementComparison.CHECK, null, null,
                    "could not fetch the provider settlement report: " + providerProblem));
        }

        Status status = providerProblem != null ? Status.INCOMPLETE
                : findings.isEmpty() ? Status.BALANCED : Status.DISCREPANCIES;
        ReconciliationReport report = new ReconciliationReport(UUID.randomUUID(), businessDate, status, triggeredBy,
                startedAt, clock.instant(), checkResults, snapshot.positions(), findings);

        transaction.executeWithoutResult(tx -> {
            jdbc.sql("""
                            INSERT INTO reconciliation_runs
                                   (id, business_date, status, findings_count, triggered_by, started_at, finished_at, report)
                            VALUES (:id, :date, :status, :findings, :triggeredBy, :startedAt, :finishedAt,
                                    CAST(:report AS jsonb))""")
                    .param("id", report.id())
                    .param("date", businessDate)
                    .param("status", status.name())
                    .param("findings", findings.size())
                    .param("triggeredBy", triggeredBy)
                    .param("startedAt", startedAt.atOffset(ZoneOffset.UTC))
                    .param("finishedAt", report.finishedAt().atOffset(ZoneOffset.UTC))
                    .param("report", json.writeValueAsString(report))
                    .update();
            audit.recordAs(triggeredBy, null, "RECONCILIATION_RUN", "reconciliation_run", report.id(),
                    businessDate + ": " + status + ", " + findings.size() + " findings");
        });
        if (status == Status.BALANCED) {
            log.info("Reconciliation {} for {}: BALANCED", report.id(), businessDate);
        } else {
            log.warn("Reconciliation {} for {}: {} with {} findings", report.id(), businessDate, status, findings.size());
        }
        return report;
    }

    public ReconciliationReport get(UUID id) {
        return jdbc.sql("SELECT report::text FROM reconciliation_runs WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .map(body -> json.readValue(body, ReconciliationReport.class))
                .orElseThrow(() -> new NotFoundException("reconciliation run " + id + " not found"));
    }

    public List<RunSummary> latest(int limit) {
        return jdbc.sql("""
                        SELECT id, business_date, status, findings_count, triggered_by, finished_at
                          FROM reconciliation_runs ORDER BY finished_at DESC LIMIT :limit""")
                .param("limit", limit)
                .query((rs, rowNum) -> new RunSummary(rs.getObject("id", UUID.class),
                        rs.getObject("business_date", LocalDate.class), Status.valueOf(rs.getString("status")),
                        rs.getInt("findings_count"), rs.getString("triggered_by"),
                        rs.getObject("finished_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static List<String> ids(List<SettledItem> items) {
        return items == null ? List.of() : items.stream().map(SettledItem::id).toList();
    }
}
