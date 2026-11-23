package com.paymentledger.payment;

import static com.paymentledger.TestApi.chargeEvent;
import static com.paymentledger.TestApi.key;
import static com.paymentledger.TestApi.newEventId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.FakePaymentProvider;
import com.paymentledger.IntegrationTest;
import com.paymentledger.TestApi;
import com.paymentledger.TestApi.Merchant;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** The top-up saga and the webhook rules: signed, exactly-once, forward-only. */
@IntegrationTest
class TopUpWebhookTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SagaRecoveryJob recovery;

    @Autowired
    PaymentSaga paymentSaga;

    @Autowired
    TransactionTemplate transaction;

    private TestApi api;
    private Merchant merchant;
    private UUID wallet;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
        wallet = api.newWallet(merchant);
    }

    @Test
    void theWalletIsCreditedOnlyWhenTheProviderConfirms() throws Exception {
        String body = api.topUp(merchant, key(), wallet, 5_000, "pm_card_ok")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(JsonPath.read(body, "$.id"));

        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);
        assertThat(provider.idempotencyKeyOf(charge.id())).isEqualTo("payment-" + paymentId);
        assertThat(api.balance(merchant, wallet)).isZero(); // accepted is not paid

        api.chargeSucceeded(charge).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));

        mvc.perform(get("/api/payments/" + paymentId).with(merchant.auth()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerChargeId").value(charge.id()));
        assertThat(api.balance(merchant, wallet)).isEqualTo(5_000);
    }

    @Test
    void duplicateAndOutOfOrderWebhooksCreditOnlyOnce() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);
        provider.settle(charge);
        String succeeded = chargeEvent(newEventId(), charge, "succeeded", 5_000);
        String staleProcessing = chargeEvent(newEventId(), charge, "processing", 5_000);

        api.deliver(succeeded).andExpect(jsonPath("$.outcome").value("APPLIED"));
        api.deliver(succeeded).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        api.deliver(staleProcessing).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        // Even a different event saying "succeeded" again can't credit twice
        api.deliver(chargeEvent(newEventId(), charge, "succeeded", 5_000))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        assertThat(api.balance(merchant, wallet)).isEqualTo(5_000);
        api.awaitPayment(merchant, paymentId, "SUCCEEDED");
    }

    @Test
    void aDeclinedCardMovesNoMoney() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_declined");
        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);

        api.deliver(chargeEvent(newEventId(), charge, "failed", 5_000)).andExpect(jsonPath("$.outcome").value("APPLIED"));
        mvc.perform(get("/api/payments/" + paymentId).with(merchant.auth()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));

        // A late, contradicting "succeeded" is flagged for a human, not obeyed
        api.deliver(chargeEvent(newEventId(), charge, "succeeded", 5_000))
                .andExpect(jsonPath("$.outcome").value("CONFLICT"));
        assertThat(api.balance(merchant, wallet)).isZero();
        mvc.perform(get("/api/audit-events").with(merchant.auth()))
                .andExpect(jsonPath("$[0].action").value("PAYMENT_PROVIDER_CONFLICT"))
                .andExpect(jsonPath("$[0].actor").value("provider"));
    }

    @Test
    void aCardTheProviderRejectsOutrightFailsAtOnce() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_invalid");

        api.awaitPayment(merchant, paymentId, "FAILED");
        assertThat((String) JsonPath.read(api.payment(merchant, paymentId), "$.failureReason")).isEqualTo("card_invalid");
        assertThat(api.balance(merchant, wallet)).isZero();
    }

    @Test
    void webhooksMustBeRecentlySignedByTheProvider() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);
        provider.settle(charge);
        String body = chargeEvent(newEventId(), charge, "succeeded", 5_000);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        api.deliver(body, null).andExpect(status().isUnauthorized());
        api.deliver(body, "garbage").andExpect(status().isUnauthorized());
        api.deliver(body, WebhookSignature.header("another-secret-0123456789abcdef0123456789", now, bytes))
                .andExpect(status().isUnauthorized());
        api.deliver(body, TestApi.signature(body, now - 3600)).andExpect(status().isUnauthorized()); // replayed later
        String tampered = body.replace("\"amountMinor\":5000", "\"amountMinor\":500000");
        api.deliver(tampered, TestApi.signature(body, now)).andExpect(status().isUnauthorized());
        assertThat(api.balance(merchant, wallet)).isZero();

        api.deliver(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(api.balance(merchant, wallet)).isEqualTo(5_000);
    }

    @Test
    void aWebhookWithAnotherAmountIsAConflict() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);

        api.deliver(chargeEvent(newEventId(), charge, "succeeded", 9_999))
                .andExpect(jsonPath("$.outcome").value("CONFLICT"));

        assertThat(api.balance(merchant, wallet)).isZero();
        assertThat((String) JsonPath.read(api.payment(merchant, paymentId), "$.status")).isEqualTo("PROCESSING");
    }

    @Test
    void theWebhookMayOvertakeTheProvidersHttpResponse() throws Exception {
        provider.holdSlowCharges();
        UUID paymentId;
        ChargeResponse charge;
        try {
            paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_slow");
            charge = provider.awaitCharge(paymentId); // the provider has it; its HTTP answer is held back
            assertThat((String) JsonPath.read(api.payment(merchant, paymentId), "$.status")).isEqualTo("CREATED");

            api.chargeSucceeded(charge).andExpect(jsonPath("$.outcome").value("APPLIED")); // CREATED -> SUCCEEDED
        } finally {
            provider.releaseSlowCharges();
        }

        // The late HTTP answer ("accepted") must not move the payment backwards
        ProviderUpdate lateAnswer = new ProviderUpdate(charge.id(), paymentId, ProviderEvent.ACCEPTED, 5_000, "EUR", null);
        UpdateOutcome outcome = transaction.execute(status -> paymentSaga.apply(lateAnswer));
        assertThat(outcome.result()).isEqualTo(UpdateOutcome.Result.IGNORED);
        api.awaitPayment(merchant, paymentId, "SUCCEEDED");
        assertThat(api.balance(merchant, wallet)).isEqualTo(5_000);
    }

    @Test
    void anUnreachableProviderIsRetriedWithTheSameIdempotencyKey() throws Exception {
        UUID paymentId;
        provider.setAvailable(false);
        try {
            paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_ok");
            UUID id = paymentId;
            TestApi.await("a first attempt", () -> provider.attempts("payment-" + id) >= 1);
            // "Outcome unknown" is not "declined": the payment waits, it doesn't fail
            assertThat((String) JsonPath.read(api.payment(merchant, paymentId), "$.status")).isEqualTo("CREATED");
        } finally {
            provider.setAvailable(true);
        }

        recovery.resubmitCreated();
        api.awaitPayment(merchant, paymentId, "PROCESSING");
        assertThat(provider.attempts("payment-" + paymentId)).isEqualTo(2);

        recovery.resubmitCreated(); // nothing left to do for this payment
        assertThat(provider.attempts("payment-" + paymentId)).isEqualTo(2);
    }

    @Test
    void aLostWebhookIsReplacedByAskingTheProvider() throws Exception {
        UUID paymentId = api.startTopUp(merchant, wallet, 5_000, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, paymentId);
        provider.settle(charge); // the provider says succeeded, but its webhook never arrives

        recovery.pollProcessing();

        api.awaitPayment(merchant, paymentId, "SUCCEEDED");
        assertThat(api.balance(merchant, wallet)).isEqualTo(5_000);
    }

    @Test
    void topUpRequestsAreIdempotent() throws Exception {
        String key = key();
        String first = api.topUp(merchant, key, wallet, 5_000, "pm_card_ok")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(JsonPath.read(first, "$.id"));

        api.topUp(merchant, key, wallet, 5_000, "pm_card_ok")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(paymentId.toString()));

        api.awaitSubmitted(merchant, paymentId);
        assertThat(provider.attempts("payment-" + paymentId)).isEqualTo(1); // the card is charged once
    }
}
