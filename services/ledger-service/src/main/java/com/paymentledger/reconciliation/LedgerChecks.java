package com.paymentledger.reconciliation;

import com.paymentledger.common.ConflictException;
import com.paymentledger.reconciliation.ReconciliationReport.CheckResult;
import com.paymentledger.reconciliation.ReconciliationReport.CurrencyPosition;
import com.paymentledger.reconciliation.ReconciliationReport.Finding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal checks: does the ledger agree with itself and with our payments and refunds?
 *
 * <p>Plain SQL on purpose. These are set-based questions over the whole ledger ("every
 * transaction whose entries don't sum to zero"), which SQL answers in one statement each. They
 * run across all merchants (an operator job), so there is no merchant filter.
 *
 * <p><b>One consistent snapshot</b>: all queries run in one {@code REPEATABLE READ} transaction,
 * so they see the database as of the same instant. Under the default READ COMMITTED, a transfer
 * committing between two queries could make correct books look wrong.
 */
@Component
public class LedgerChecks {

    private static final int MAX_FINDINGS_PER_CHECK = 100;

    /** Our side of a provider-settled item, for the comparison in {@link SettlementComparison}. */
    public record OurItem(UUID merchantId, UUID id, String providerId, String status, long amountMinor, String currency) {}

    /** Everything one run needs from the database, read in one snapshot. */
    public record Snapshot(List<CheckResult> checks, List<Finding> findings, List<CurrencyPosition> positions,
                           List<OurItem> paymentsSettledOnDate, Map<String, OurItem> paymentsByChargeId,
                           List<OurItem> refundsSettledOnDate, Map<String, OurItem> refundsByProviderId) {}

    private final JdbcClient jdbc;

    public LedgerChecks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(LocalDate businessDate, Collection<String> providerChargeIds,
                             Collection<String> providerRefundIds) {
        // Only one reconciliation at a time, across all instances of this service. The lock is
        // released automatically when this transaction ends.
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('ledger-reconciliation'))")
                .query(Boolean.class).single();
        if (!locked) {
            throw new ConflictException("reconciliation_running", "another reconciliation is running; try again shortly");
        }

        List<CheckResult> checks = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        run(checks, findings, "entries_sum_to_zero", "every ledger transaction's entries sum to zero", """
                SELECT t.merchant_id, t.id::text AS reference,
                       'entries sum to ' || sum(e.amount_minor) || ' instead of 0' AS message
                  FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id = t.id
                 GROUP BY t.merchant_id, t.id
                HAVING sum(e.amount_minor) <> 0""");

        run(checks, findings, "transactions_have_two_entries", "every ledger transaction has at least two entries", """
                SELECT t.merchant_id, t.id::text AS reference, 'only ' || count(e.id) || ' entries' AS message
                  FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                 GROUP BY t.merchant_id, t.id
                HAVING count(e.id) < 2""");

        run(checks, findings, "balances_match_entries", "every cached account balance equals the sum of its entries", """
                SELECT a.merchant_id, a.id::text AS reference,
                       a.type || ' balance is ' || a.balance_minor || ' but its entries add up to ' || d.derived AS message
                  FROM ledger_accounts a
                  JOIN LATERAL (
                        SELECT coalesce(sum(CASE a.normal_balance WHEN 'DEBIT' THEN e.amount_minor
                                                                  ELSE -e.amount_minor END), 0) AS derived
                          FROM ledger_entries e WHERE e.account_id = a.id) d ON true
                 WHERE a.balance_minor <> d.derived""");

        run(checks, findings, "no_negative_balances", "no wallet or in-transit account is below zero", """
                SELECT merchant_id, id::text AS reference, type || ' balance is ' || balance_minor AS message
                  FROM ledger_accounts
                 WHERE NOT allow_negative AND balance_minor < 0""");

        run(checks, findings, "trial_balance", "all debits equal all credits, per merchant and currency", """
                SELECT merchant_id, currency AS reference, 'entries sum to ' || sum(amount_minor) AS message
                  FROM ledger_entries
                 GROUP BY merchant_id, currency
                HAVING sum(amount_minor) <> 0""");

        run(checks, findings, "payments_match_ledger",
                "each SUCCEEDED payment has exactly one TOP_UP crediting its amount; other payments have none", """
                SELECT p.merchant_id, p.id::text AS reference,
                       p.status || ' payment of ' || p.amount_minor || ' has ' || x.postings
                           || ' TOP_UP postings crediting ' || x.credited AS message
                  FROM payments p
                  JOIN LATERAL (
                        SELECT count(DISTINCT t.id) AS postings, coalesce(-sum(e.amount_minor), 0) AS credited
                          FROM ledger_transactions t
                          JOIN ledger_entries e ON e.transaction_id = t.id
                          JOIN wallets w ON w.ledger_account_id = e.account_id
                         WHERE t.type = 'TOP_UP' AND t.reference_id = p.id) x ON true
                 WHERE (p.status = 'SUCCEEDED' AND (x.postings <> 1 OR x.credited <> p.amount_minor))
                    OR (p.status <> 'SUCCEEDED' AND x.postings <> 0)""");

        run(checks, findings, "refunds_match_ledger",
                "each refund has one reservation, plus one settlement if SUCCEEDED or one reversal if FAILED", """
                SELECT r.merchant_id, r.id::text AS reference,
                       r.status || ' refund has ' || x.reserves || ' reserve, ' || x.settles || ' settle, '
                           || x.reversals || ' reversal postings' AS message
                  FROM refunds r
                  JOIN LATERAL (
                        SELECT count(*) FILTER (WHERE t.type = 'REFUND_RESERVE')  AS reserves,
                               count(*) FILTER (WHERE t.type = 'REFUND_SETTLE')   AS settles,
                               count(*) FILTER (WHERE t.type = 'REFUND_REVERSAL') AS reversals
                          FROM ledger_transactions t WHERE t.reference_id = r.id) x ON true
                 WHERE NOT (x.reserves = 1 AND CASE r.status
                            WHEN 'SUCCEEDED' THEN x.settles = 1 AND x.reversals = 0
                            WHEN 'FAILED'    THEN x.settles = 0 AND x.reversals = 1
                            ELSE                  x.settles = 0 AND x.reversals = 0 END)""");

        run(checks, findings, "refunded_amounts_match",
                "each payment's refunded amount equals the sum of its refunds that did not fail", """
                SELECT p.merchant_id, p.id::text AS reference,
                       'payment says ' || p.refunded_minor || ' refunded, refunds add up to ' || x.total AS message
                  FROM payments p
                  JOIN LATERAL (
                        SELECT coalesce(sum(r.amount_minor), 0) AS total
                          FROM refunds r WHERE r.payment_id = p.id AND r.status <> 'FAILED') x ON true
                 WHERE p.refunded_minor <> x.total""");

        List<CurrencyPosition> positions = positions();
        int unbalanced = 0;
        for (CurrencyPosition position : positions) {
            if (!position.balanced()) {
                unbalanced++;
                findings.add(new Finding("assets_equal_liabilities", position.merchantId(), position.currency(),
                        "provider clearing " + position.providerClearingMinor() + " <> wallets " + position.walletsMinor()
                                + " + refunds in transit " + position.refundsInTransitMinor()));
            }
        }
        checks.add(new CheckResult("assets_equal_liabilities",
                "cash at the provider equals wallets plus refunds in transit, per merchant and currency", unbalanced));

        OffsetDateTime from = businessDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);
        return new Snapshot(checks, findings, positions,
                ourItems("""
                        SELECT merchant_id, id, provider_charge_id AS provider_id, status, amount_minor, currency
                          FROM payments WHERE status = 'SUCCEEDED' AND succeeded_at >= :from AND succeeded_at < :to""",
                        Map.of("from", from, "to", to)),
                byProviderId(providerChargeIds, """
                        SELECT merchant_id, id, provider_charge_id AS provider_id, status, amount_minor, currency
                          FROM payments WHERE provider_charge_id IN (:ids)"""),
                ourItems("""
                        SELECT merchant_id, id, provider_refund_id AS provider_id, status, amount_minor, currency
                          FROM refunds WHERE status = 'SUCCEEDED' AND completed_at >= :from AND completed_at < :to""",
                        Map.of("from", from, "to", to)),
                byProviderId(providerRefundIds, """
                        SELECT merchant_id, id, provider_refund_id AS provider_id, status, amount_minor, currency
                          FROM refunds WHERE provider_refund_id IN (:ids)"""));
    }

    /** Runs one check query (columns: merchant_id, reference, message) and records its findings. */
    private void run(List<CheckResult> checks, List<Finding> findings, String name, String description, String sql) {
        List<Finding> found = jdbc.sql(sql + "\nLIMIT " + MAX_FINDINGS_PER_CHECK)
                .query((rs, rowNum) -> new Finding(name, rs.getObject("merchant_id", UUID.class),
                        rs.getString("reference"), rs.getString("message")))
                .list();
        checks.add(new CheckResult(name, description, found.size()));
        findings.addAll(found);
    }

    private List<CurrencyPosition> positions() {
        record Row(UUID merchantId, String currency, String type, long total) {}
        List<Row> rows = jdbc.sql("""
                        SELECT merchant_id, currency, type, sum(balance_minor) AS total
                          FROM ledger_accounts GROUP BY merchant_id, currency, type
                         ORDER BY merchant_id, currency""")
                .query((rs, rowNum) -> new Row(rs.getObject("merchant_id", UUID.class), rs.getString("currency"),
                        rs.getString("type"), rs.getLong("total")))
                .list();
        Map<String, Map<String, Long>> byKey = new LinkedHashMap<>();
        Map<String, Row> keyRow = new HashMap<>();
        for (Row row : rows) {
            String key = row.merchantId() + "/" + row.currency();
            byKey.computeIfAbsent(key, k -> new HashMap<>()).put(row.type(), row.total());
            keyRow.putIfAbsent(key, row);
        }
        List<CurrencyPosition> positions = new ArrayList<>();
        byKey.forEach((key, totals) -> {
            long clearing = totals.getOrDefault("PROVIDER_CLEARING", 0L);
            long wallets = totals.getOrDefault("WALLET", 0L);
            long inTransit = totals.getOrDefault("REFUNDS_IN_TRANSIT", 0L);
            Row row = keyRow.get(key);
            positions.add(new CurrencyPosition(row.merchantId(), row.currency(), clearing, wallets, inTransit,
                    clearing == wallets + inTransit));
        });
        return positions;
    }

    private List<OurItem> ourItems(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query(OUR_ITEM).list();
    }

    private Map<String, OurItem> byProviderId(Collection<String> providerIds, String sql) {
        if (providerIds.isEmpty()) {
            return Map.of(); // "IN ()" is a syntax error
        }
        return jdbc.sql(sql).param("ids", List.copyOf(providerIds)).query(OUR_ITEM).list().stream()
                .collect(Collectors.toMap(OurItem::providerId, Function.identity()));
    }

    private static final RowMapper<OurItem> OUR_ITEM = LedgerChecks::ourItem;

    private static OurItem ourItem(ResultSet rs, int rowNum) throws SQLException {
        return new OurItem(rs.getObject("merchant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("provider_id"), rs.getString("status"), rs.getLong("amount_minor"), rs.getString("currency"));
    }
}
