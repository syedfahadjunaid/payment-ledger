package com.paymentledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Drives the real HTTP API through MockMvc, so tests read like a merchant's integration. */
public final class TestApi {

    public static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef0123456789";
    public static final String WEBHOOK_SECRET = "test-webhook-secret-0123456789abcdef0123";

    /** A merchant created through the admin API, with its secret key. */
    public record Merchant(UUID id, String apiKey) {
        public RequestPostProcessor auth() {
            return bearer(apiKey);
        }
    }

    private final MockMvc mvc;
    private final FakePaymentProvider provider;

    public TestApi(MockMvc mvc, FakePaymentProvider provider) {
        this.mvc = mvc;
        this.provider = provider;
    }

    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    public static RequestPostProcessor admin() {
        return bearer(ADMIN_TOKEN);
    }

    public static String key() {
        return "key-" + UUID.randomUUID();
    }

    public static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    // ---- merchants and wallets ----------------------------------------------------------------

    public Merchant newMerchant() throws Exception {
        String body = mvc.perform(post("/admin/merchants").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test shop " + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Merchant(UUID.fromString(JsonPath.read(body, "$.merchantId")), JsonPath.read(body, "$.apiKey"));
    }

    public UUID newWallet(Merchant merchant) throws Exception {
        return newWallet(merchant, "EUR");
    }

    public UUID newWallet(Merchant merchant, String currency) throws Exception {
        String body = mvc.perform(post("/api/wallets").with(merchant.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerRef\":\"customer-%s\",\"currency\":\"%s\"}".formatted(UUID.randomUUID(), currency)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public long balance(Merchant merchant, UUID walletId) throws Exception {
        String body = mvc.perform(get("/api/wallets/" + walletId).with(merchant.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.balance.amountMinor")).longValue();
    }

    // ---- transfers ----------------------------------------------------------------------------

    public ResultActions transfer(Merchant merchant, String key, UUID from, UUID to, long amount) throws Exception {
        return transfer(merchant, key, from, to, amount, "EUR");
    }

    public ResultActions transfer(Merchant merchant, String key, UUID from, UUID to, long amount, String currency)
            throws Exception {
        return mvc.perform(withKey(post("/api/transfers"), key).with(merchant.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromWalletId":"%s","toWalletId":"%s","amountMinor":%d,"currency":"%s","description":"test"}"""
                        .formatted(from, to, amount, currency)));
    }

    // ---- top-ups ------------------------------------------------------------------------------

    public ResultActions topUp(Merchant merchant, String key, UUID walletId, long amount, String paymentMethod)
            throws Exception {
        return mvc.perform(withKey(post("/api/wallets/" + walletId + "/top-ups"), key).with(merchant.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amountMinor":%d,"currency":"EUR","paymentMethod":"%s"}""".formatted(amount, paymentMethod)));
    }

    public UUID startTopUp(Merchant merchant, UUID walletId, long amount, String paymentMethod) throws Exception {
        String body = topUp(merchant, key(), walletId, amount, paymentMethod)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Waits until the saga has handed the payment to the provider and recorded the answer. */
    public ChargeResponse awaitSubmitted(Merchant merchant, UUID paymentId) throws Exception {
        ChargeResponse charge = provider.awaitCharge(paymentId);
        awaitPayment(merchant, paymentId, "PROCESSING");
        return charge;
    }

    /** A top-up all the way through: provider accepted, then the webhook says it succeeded. */
    public UUID fund(Merchant merchant, UUID walletId, long amount) throws Exception {
        return fund(merchant, walletId, amount, "pm_card_ok");
    }

    public UUID fund(Merchant merchant, UUID walletId, long amount, String paymentMethod) throws Exception {
        UUID paymentId = startTopUp(merchant, walletId, amount, paymentMethod);
        ChargeResponse charge = awaitSubmitted(merchant, paymentId);
        chargeSucceeded(charge).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        awaitPayment(merchant, paymentId, "SUCCEEDED");
        return paymentId;
    }

    public String payment(Merchant merchant, UUID paymentId) throws Exception {
        return mvc.perform(get("/api/payments/" + paymentId).with(merchant.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    public void awaitPayment(Merchant merchant, UUID paymentId, String expectedStatus) throws Exception {
        await("payment " + paymentId + " to be " + expectedStatus,
                () -> expectedStatus.equals(JsonPath.read(payment(merchant, paymentId), "$.status")));
    }

    // ---- refunds ------------------------------------------------------------------------------

    public ResultActions refund(Merchant merchant, String key, UUID paymentId, long amount) throws Exception {
        return mvc.perform(withKey(post("/api/payments/" + paymentId + "/refunds"), key).with(merchant.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":%d,\"reason\":\"customer request\"}".formatted(amount)));
    }

    public UUID startRefund(Merchant merchant, UUID paymentId, long amount) throws Exception {
        String body = refund(merchant, key(), paymentId, amount)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public void awaitRefund(Merchant merchant, UUID refundId, String expectedStatus) throws Exception {
        await("refund " + refundId + " to be " + expectedStatus, () -> {
            String body = mvc.perform(get("/api/refunds/" + refundId).with(merchant.auth()))
                    .andReturn().getResponse().getContentAsString();
            return expectedStatus.equals(JsonPath.read(body, "$.status"));
        });
    }

    // ---- webhooks -----------------------------------------------------------------------------

    public ResultActions chargeSucceeded(ChargeResponse charge) throws Exception {
        provider.settle(charge);
        return deliver(chargeEvent(newEventId(), charge, "succeeded", charge.amountMinor()));
    }

    public static String chargeEvent(String eventId, ChargeResponse charge, String status, long amountMinor) {
        return """
                {"id":"%s","type":"charge.%s","createdAt":"%s","data":{"objectType":"charge","id":"%s",\
                "reference":"%s","status":"%s","amountMinor":%d,"currency":"%s","failureReason":%s}}"""
                .formatted(eventId, status, Instant.now(), charge.id(), charge.reference(), status, amountMinor,
                        charge.currency(), "failed".equals(status) ? "\"card_declined\"" : "null");
    }

    public static String refundEvent(String eventId, RefundResponse refund, String status) {
        return """
                {"id":"%s","type":"refund.%s","createdAt":"%s","data":{"objectType":"refund","id":"%s",\
                "chargeId":"%s","reference":"%s","status":"%s","amountMinor":%d,"currency":"%s","failureReason":%s}}"""
                .formatted(eventId, status, Instant.now(), refund.id(), refund.chargeId(), refund.reference(), status,
                        refund.amountMinor(), refund.currency(),
                        "failed".equals(status) ? "\"refund_rejected_by_issuer\"" : "null");
    }

    /** Delivers a webhook signed now with the right secret. */
    public ResultActions deliver(String body) throws Exception {
        return deliver(body, signature(body, Instant.now().getEpochSecond()));
    }

    public ResultActions deliver(String body, String signatureHeader) throws Exception {
        MockHttpServletRequestBuilder request = post("/webhooks/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (signatureHeader != null) {
            request.header(WebhookSignature.HEADER, signatureHeader);
        }
        return mvc.perform(request);
    }

    public static String signature(String body, long timestampSeconds) {
        return WebhookSignature.header(WEBHOOK_SECRET, timestampSeconds, body.getBytes(StandardCharsets.UTF_8));
    }

    // ---- reconciliation -----------------------------------------------------------------------

    public String reconcile() throws Exception {
        return mvc.perform(post("/admin/reconciliations").with(admin()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** The report's findings about this merchant. Other tests' data may produce their own findings. */
    public static List<Map<String, Object>> findingsFor(String report, Merchant merchant) {
        return JsonPath.read(report, "$.findings[?(@.merchantId == '" + merchant.id() + "')]");
    }

    public static List<Map<String, Object>> positionsFor(String report, Merchant merchant) {
        return JsonPath.read(report, "$.positions[?(@.merchantId == '" + merchant.id() + "')]");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key) {
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    public static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
