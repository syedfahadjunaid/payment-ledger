package com.paymentledger.reconciliation;

import static com.paymentledger.TestApi.chargeEvent;
import static com.paymentledger.TestApi.key;
import static com.paymentledger.TestApi.newEventId;
import static com.paymentledger.TestApi.refundEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.FakePaymentProvider;
import com.paymentledger.IntegrationTest;
import com.paymentledger.Superuser;
import com.paymentledger.TestApi;
import com.paymentledger.TestApi.Merchant;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The reconciliation job proves the books balance, and notices when they don't, even when the
 * damage was done behind the application's back. Other tests share the database, so each test
 * looks only at findings about its own merchant.
 */
@IntegrationTest
class ReconciliationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    Superuser superuser;

    private TestApi api;
    private Merchant merchant;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
        alice = api.newWallet(merchant);
        bob = api.newWallet(merchant);
    }

    @Test
    void topUpTransferAndRefundReconcileCleanly() throws Exception {
        UUID payment = api.fund(merchant, alice, 10_000);
        api.transfer(merchant, key(), alice, bob, 2_500).andExpect(status().isCreated());
        UUID refundId = api.startRefund(merchant, payment, 1_000);
        RefundResponse refund = provider.awaitRefund(refundId);
        api.awaitRefund(merchant, refundId, "PROCESSING");
        provider.settle(refund);
        api.deliver(refundEvent(newEventId(), refund, "succeeded")).andExpect(jsonPath("$.outcome").value("APPLIED"));

        String report = api.reconcile();

        assertThat(TestApi.findingsFor(report, merchant)).isEmpty();
        // The balance sheet: 90.00 held at the provider = 90.00 owed to the two customers
        List<Map<String, Object>> positions = TestApi.positionsFor(report, merchant);
        assertThat(positions).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("currency", "EUR").containsEntry("balanced", true);
            assertThat(((Number) p.get("providerClearingMinor")).longValue()).isEqualTo(9_000);
            assertThat(((Number) p.get("walletsMinor")).longValue()).isEqualTo(9_000);
            assertThat(((Number) p.get("refundsInTransitMinor")).longValue()).isZero();
        });
        List<String> checks = JsonPath.read(report, "$.checks[*].name");
        assertThat(checks).contains("entries_sum_to_zero", "transactions_have_two_entries", "balances_match_entries",
                "no_negative_balances", "trial_balance", "payments_match_ledger", "refunds_match_ledger",
                "refunded_amounts_match", "assets_equal_liabilities", "provider_settlement");

        // Each run is stored and can be read back
        String id = JsonPath.read(report, "$.id");
        mvc.perform(get("/admin/reconciliations/" + id).with(TestApi.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value((String) JsonPath.read(report, "$.status")));
        mvc.perform(get("/admin/reconciliations").with(TestApi.admin()))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void noticesABalanceChangedBehindTheLedgersBack() throws Exception {
        api.fund(merchant, alice, 5_000);
        UUID account = superuser.jdbc().sql("SELECT ledger_account_id FROM ledger.wallets WHERE id = ?")
                .param(alice).query(UUID.class).single();

        // Only a superuser with triggers switched off can do this
        superuser.runWithoutTriggers("UPDATE ledger.ledger_accounts SET balance_minor = balance_minor + 1000000 WHERE id = ?",
                account);
        try {
            String report = api.reconcile();

            assertThat((String) JsonPath.read(report, "$.status")).isEqualTo("DISCREPANCIES");
            assertThat(TestApi.findingsFor(report, merchant))
                    .extracting(f -> f.get("check"))
                    .contains("balances_match_entries", "assets_equal_liabilities");
            assertThat(TestApi.findingsFor(report, merchant))
                    .anySatisfy(f -> assertThat(f).containsEntry("reference", account.toString()));
        } finally {
            superuser.runWithoutTriggers(
                    "UPDATE ledger.ledger_accounts SET balance_minor = balance_minor - 1000000 WHERE id = ?", account);
        }
        assertThat(TestApi.findingsFor(api.reconcile(), merchant)).isEmpty();
    }

    @Test
    void noticesACreditTheProviderNeverSettled() throws Exception {
        UUID payment = api.startTopUp(merchant, alice, 3_000, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, payment);
        // A validly signed webhook credits the wallet, but the provider's own statement doesn't list it
        api.deliver(chargeEvent(newEventId(), charge, "succeeded", 3_000)).andExpect(jsonPath("$.outcome").value("APPLIED"));

        String report = api.reconcile();

        assertThat(TestApi.findingsFor(report, merchant)).singleElement().satisfies(f -> {
            assertThat(f).containsEntry("check", "provider_settlement").containsEntry("reference", payment.toString());
            assertThat((String) f.get("message")).contains("did not settle");
        });
    }

    @Test
    void onlyOperatorsMayReconcile() throws Exception {
        mvc.perform(post("/admin/reconciliations").with(merchant.auth())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reconciliations")).andExpect(status().isUnauthorized());
    }
}
