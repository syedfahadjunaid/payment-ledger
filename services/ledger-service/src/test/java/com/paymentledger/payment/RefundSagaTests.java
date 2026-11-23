package com.paymentledger.payment;

import static com.paymentledger.TestApi.key;
import static com.paymentledger.TestApi.newEventId;
import static com.paymentledger.TestApi.refundEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.FakePaymentProvider;
import com.paymentledger.IntegrationTest;
import com.paymentledger.TestApi;
import com.paymentledger.TestApi.Merchant;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The refund saga: reserve, then settle or compensate. */
@IntegrationTest
class RefundSagaTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    private TestApi api;
    private Merchant merchant;
    private UUID wallet;
    private UUID payment;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
        wallet = api.newWallet(merchant);
        payment = api.fund(merchant, wallet, 10_000);
    }

    @Test
    void aRefundReservesTheMoneyAtOnceAndSettlesLater() throws Exception {
        UUID refundId = api.startRefund(merchant, payment, 4_000);

        // The money leaves the wallet immediately, so the customer can't spend it meanwhile
        assertThat(api.balance(merchant, wallet)).isEqualTo(6_000);
        RefundResponse refund = provider.awaitRefund(refundId);
        assertThat(provider.idempotencyKeyOf(refund.id())).isEqualTo("refund-" + refundId);
        api.awaitRefund(merchant, refundId, "PROCESSING");

        provider.settle(refund);
        api.deliver(refundEvent(newEventId(), refund, "succeeded")).andExpect(jsonPath("$.outcome").value("APPLIED"));

        mvc.perform(get("/api/payments/" + payment).with(merchant.auth()))
                .andExpect(jsonPath("$.refunded.amountMinor").value(4000))
                .andExpect(jsonPath("$.refunds[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refunds[0].providerRefundId").value(refund.id()));
        assertThat(api.balance(merchant, wallet)).isEqualTo(6_000);
        mvc.perform(get("/api/wallets/" + wallet + "/entries").with(merchant.auth()))
                .andExpect(jsonPath("$[0].type").value("REFUND_RESERVE"))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(-4000))
                .andExpect(jsonPath("$.length()").value(2)); // the settlement doesn't touch the wallet
    }

    @Test
    void aFailedRefundIsCompensated() throws Exception {
        UUID refundId = api.startRefund(merchant, payment, 4_000);
        RefundResponse refund = provider.awaitRefund(refundId);
        api.awaitRefund(merchant, refundId, "PROCESSING");

        api.deliver(refundEvent(newEventId(), refund, "failed")).andExpect(jsonPath("$.outcome").value("APPLIED"));

        mvc.perform(get("/api/refunds/" + refundId).with(merchant.auth()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("refund_rejected_by_issuer"));
        assertThat(api.balance(merchant, wallet)).isEqualTo(10_000);
        assertThat(((Number) JsonPath.read(api.payment(merchant, payment), "$.refunded.amountMinor")).longValue()).isZero();
        // History is never rewritten: the reversal is a new entry next to the reservation
        mvc.perform(get("/api/wallets/" + wallet + "/entries").with(merchant.auth()))
                .andExpect(jsonPath("$[0].type").value("REFUND_REVERSAL"))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(4000))
                .andExpect(jsonPath("$[1].type").value("REFUND_RESERVE"))
                .andExpect(jsonPath("$[1].amount.amountMinor").value(-4000));

        // The full amount can be refunded again
        api.refund(merchant, key(), payment, 10_000).andExpect(status().isAccepted());
    }

    @Test
    void aRefundTheProviderRefusesOutrightIsCompensatedAtOnce() throws Exception {
        UUID unrefundable = api.fund(merchant, wallet, 2_000, "pm_card_norefund");

        UUID refundId = api.startRefund(merchant, unrefundable, 2_000);

        api.awaitRefund(merchant, refundId, "FAILED");
        assertThat(api.balance(merchant, wallet)).isEqualTo(12_000);
        assertThat(((Number) JsonPath.read(api.payment(merchant, unrefundable), "$.refunded.amountMinor")).longValue())
                .isZero();
    }

    @Test
    void refundsCannotExceedThePayment() throws Exception {
        api.refund(merchant, key(), payment, 6_000).andExpect(status().isAccepted());
        api.refund(merchant, key(), payment, 4_001)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("refund_exceeds_payment"));
        api.refund(merchant, key(), payment, 4_000).andExpect(status().isAccepted());
        api.refund(merchant, key(), payment, 1).andExpect(jsonPath("$.code").value("refund_exceeds_payment"));
        assertThat(api.balance(merchant, wallet)).isZero();
    }

    @Test
    void onlySucceededPaymentsCanBeRefunded() throws Exception {
        UUID declined = api.startTopUp(merchant, wallet, 1_000, "pm_card_invalid");
        api.awaitPayment(merchant, declined, "FAILED");

        api.refund(merchant, key(), declined, 1_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payment_not_refundable"));
    }

    @Test
    void moneyTheCustomerAlreadySpentCannotBeRefunded() throws Exception {
        UUID shop = api.newWallet(merchant);
        api.transfer(merchant, key(), wallet, shop, 9_000).andExpect(status().isCreated());

        api.refund(merchant, key(), payment, 5_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient_funds"));
        // Nothing half-done: the payment's refundable amount is untouched
        assertThat(((Number) JsonPath.read(api.payment(merchant, payment), "$.refunded.amountMinor")).longValue()).isZero();
        assertThat(api.balance(merchant, wallet)).isEqualTo(1_000);
    }

    @Test
    void paymentsOfOtherMerchantsAreInvisible() throws Exception {
        Merchant other = api.newMerchant();

        api.refund(other, key(), payment, 100).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/" + payment).with(other.auth())).andExpect(status().isNotFound());
    }
}
