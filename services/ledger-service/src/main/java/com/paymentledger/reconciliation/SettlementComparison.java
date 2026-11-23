package com.paymentledger.reconciliation;

import com.paymentledger.provider.ProviderDtos.SettledItem;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import com.paymentledger.reconciliation.LedgerChecks.OurItem;
import com.paymentledger.reconciliation.LedgerChecks.Snapshot;
import com.paymentledger.reconciliation.ReconciliationReport.Finding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External reconciliation: our records against the provider's settlement report for one day.
 * A pure function (no database, no HTTP), so it's easy to unit test.
 *
 * <p>Three kinds of discrepancy, in both directions:
 * <ul>
 *   <li>the provider settled something we didn't record as SUCCEEDED (we may owe a customer money);</li>
 *   <li>we recorded something as SUCCEEDED that the provider didn't settle (we may have credited
 *       a wallet with money we never received);</li>
 *   <li>both have it, but the amounts or currencies differ.</li>
 * </ul>
 * Around midnight a webhook can land on the next day; such "timing differences" show up here and
 * clear on the next day's run.
 */
public final class SettlementComparison {

    public static final String CHECK = "provider_settlement";

    private SettlementComparison() {}

    public static List<Finding> compare(SettlementReport report, Snapshot ours) {
        List<Finding> findings = new ArrayList<>();
        compare("charge", report.charges(), ours.paymentsByChargeId(), ours.paymentsSettledOnDate(), findings);
        compare("refund", report.refunds(), ours.refundsByProviderId(), ours.refundsSettledOnDate(), findings);
        return findings;
    }

    private static void compare(String kind, List<SettledItem> theirs, Map<String, OurItem> oursByProviderId,
                                List<OurItem> oursSettledOnDate, List<Finding> findings) {
        Set<String> theirIds = new HashSet<>();
        for (SettledItem item : theirs == null ? List.<SettledItem>of() : theirs) {
            theirIds.add(item.id());
            OurItem our = oursByProviderId.get(item.id());
            if (our == null) {
                findings.add(new Finding(CHECK, null, item.id(),
                        "provider settled " + kind + " " + item.id() + " that we have no record of"));
            } else if (!"SUCCEEDED".equals(our.status())) {
                findings.add(new Finding(CHECK, our.merchantId(), our.id().toString(),
                        "provider settled " + kind + " " + item.id() + " but we recorded it as " + our.status()));
            } else if (our.amountMinor() != item.amountMinor() || !our.currency().equals(item.currency())) {
                findings.add(new Finding(CHECK, our.merchantId(), our.id().toString(),
                        kind + " " + item.id() + ": provider settled " + item.amountMinor() + " " + item.currency()
                                + ", we recorded " + our.amountMinor() + " " + our.currency()));
            }
        }
        for (OurItem our : oursSettledOnDate) {
            if (our.providerId() == null || !theirIds.contains(our.providerId())) {
                findings.add(new Finding(CHECK, our.merchantId(), our.id().toString(),
                        "we recorded " + kind + " " + our.providerId() + " as SUCCEEDED but the provider did not settle it"));
            }
        }
    }
}
