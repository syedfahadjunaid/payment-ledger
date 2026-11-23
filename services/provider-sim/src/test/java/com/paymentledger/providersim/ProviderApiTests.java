package com.paymentledger.providersim;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The simulator's API, with webhooks pointed at a closed port (deliveries just fail). */
@SpringBootTest(properties = {
        "sim.api-key=test-provider-key",
        "sim.webhook-secret=test-webhook-secret-0123456789abcdef0123",
        "sim.webhook-url=http://127.0.0.1:9/webhooks",
        "sim.settle-delay=100ms",
        "sim.max-delivery-attempts=1"})
@AutoConfigureMockMvc
class ProviderApiTests {

    private static final String AUTH = "Bearer test-provider-key";

    @Autowired
    MockMvc mvc;

    @Test
    void everyCallNeedsTheApiKey() throws Exception {
        mvc.perform(get("/v1/charges/ch_x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/charges/ch_x").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameCharge() throws Exception {
        String key = "payment-" + UUID.randomUUID();
        String first = charge(key, "pm_card_ok", 1000).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");

        charge(key, "pm_card_ok", 1000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        charge("k-" + UUID.randomUUID(), "pm_unknown", 1000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_payment_method"));
        charge("k-" + UUID.randomUUID(), "pm_card_invalid", 1000)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("card_invalid"));
        charge("k-" + UUID.randomUUID(), "pm_card_ok", 0)
                .andExpect(status().isBadRequest());
        charge(null, "pm_card_ok", 1000)
                .andExpect(jsonPath("$.error").value("missing_idempotency_key"));
    }

    @Test
    void aSettledChargeAppearsInTheSettlementReportAndCanBeRefundedOnce() throws Exception {
        String body = charge("k-" + UUID.randomUUID(), "pm_card_ok", 2500).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        awaitStatus("/v1/charges/" + id, "succeeded");

        mvc.perform(get("/v1/settlements").param("date", LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).toString())
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charges[*].id", hasItem(id)));

        refund(id, 2000).andExpect(status().isCreated());
        refund(id, 600)   // only 500 left
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("amount_exceeds_charge"));
    }

    private ResultActions charge(String key, String paymentMethod, long amount) throws Exception {
        var request = post("/v1/charges").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amountMinor":%d,"currency":"EUR","paymentMethod":"%s","reference":"%s"}"""
                        .formatted(amount, paymentMethod, UUID.randomUUID()));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private ResultActions refund(String chargeId, long amount) throws Exception {
        return mvc.perform(post("/v1/refunds").header("Authorization", AUTH)
                .header("Idempotency-Key", "refund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"chargeId":"%s","amountMinor":%d,"reference":"%s"}""".formatted(chargeId, amount, UUID.randomUUID())));
    }

    private void awaitStatus(String path, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String body = mvc.perform(get(path).header("Authorization", AUTH)).andReturn().getResponse().getContentAsString();
            if (expected.equals(JsonPath.read(body, "$.status"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(path + " never reached " + expected);
    }
}
