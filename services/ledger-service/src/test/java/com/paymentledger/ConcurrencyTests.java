package com.paymentledger;

import static com.paymentledger.TestApi.chargeEvent;
import static com.paymentledger.TestApi.newEventId;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.paymentledger.TestApi.Merchant;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hammers single wallets from many threads at the same instant, over real HTTP (real Tomcat
 * threads, a real connection pool, real row locks), then checks the money invariants:
 * no overdraft, no lost update, no deadlock, nothing applied twice, and the books still balance.
 */
@IntegrationTest
class ConcurrencyTests {

    private static final int THREADS = 32;

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    FakePaymentProvider provider;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private TestApi api;
    private Merchant merchant;

    private record Response(int status, String body, String replayed) {}

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, provider);
        merchant = api.newMerchant();
    }

    @Test
    void simultaneousTransfersFromOneWalletNeverOverdrawIt() throws Exception {
        UUID source = api.newWallet(merchant);
        UUID target = api.newWallet(merchant);
        api.fund(merchant, source, 1_000);

        // 40 transfers of 1.00 from a wallet holding 10.00, all released at once
        List<Response> results = concurrently(IntStream.range(0, 40)
                .mapToObj(i -> (Callable<Response>) () -> transfer(source, target, 100, TestApi.key()))
                .toList());

        assertThat(results).extracting(Response::status).containsOnly(201, 422);
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(10);
        assertThat(api.balance(merchant, source)).isZero();
        assertThat(api.balance(merchant, target)).isEqualTo(1_000);
        assertBooksBalance();
    }

    @Test
    void crossingTransfersNeitherDeadlockNorLoseUpdates() throws Exception {
        UUID a = api.newWallet(merchant);
        UUID b = api.newWallet(merchant);
        api.fund(merchant, a, 5_000);
        api.fund(merchant, b, 5_000);

        // A -> B and B -> A at the same time: without a fixed lock order these would deadlock
        record Planned(boolean aToB, long amount) {}
        Random random = new Random(42);
        List<Planned> plan = IntStream.range(0, 80)
                .mapToObj(i -> new Planned(random.nextBoolean(), 1 + random.nextInt(300)))
                .toList();
        List<Response> results = concurrently(plan.stream()
                .map(p -> (Callable<Response>) () -> p.aToB()
                        ? transfer(a, b, p.amount(), TestApi.key())
                        : transfer(b, a, p.amount(), TestApi.key()))
                .toList());

        // 409 would mean a deadlock or lock timeout, 500 anything worse (422 = not enough money, fine)
        assertThat(results).extracting(Response::status).isSubsetOf(201, 422);
        long expectedA = 5_000;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).status() == 201) {
                expectedA += plan.get(i).aToB() ? -plan.get(i).amount() : plan.get(i).amount();
            }
        }
        assertThat(api.balance(merchant, a)).isEqualTo(expectedA);              // no lost update
        assertThat(api.balance(merchant, b)).isEqualTo(10_000 - expectedA);     // money is conserved
        assertBooksBalance();
    }

    @Test
    void theSameIdempotencyKeyFromManyThreadsMovesMoneyOnce() throws Exception {
        UUID source = api.newWallet(merchant);
        UUID target = api.newWallet(merchant);
        api.fund(merchant, source, 1_000);
        String key = TestApi.key();

        List<Response> results = concurrently(IntStream.range(0, 20)
                .mapToObj(i -> (Callable<Response>) () -> transfer(source, target, 250, key))
                .toList());

        assertThat(results).extracting(Response::status).containsOnly(201);
        assertThat(results).extracting(r -> (String) JsonPath.read(r.body(), "$.id")).containsOnly(
                (String) JsonPath.read(results.getFirst().body(), "$.id"));
        assertThat(results).filteredOn(r -> "false".equals(r.replayed())).hasSize(1);
        assertThat(api.balance(merchant, source)).isEqualTo(750);
        assertBooksBalance();
    }

    @Test
    void simultaneousRefundsCannotExceedThePayment() throws Exception {
        UUID wallet = api.newWallet(merchant);
        UUID payment = api.fund(merchant, wallet, 1_000);

        List<Response> results = concurrently(IntStream.range(0, 12)
                .mapToObj(i -> (Callable<Response>) () -> post("/api/payments/" + payment + "/refunds", TestApi.key(),
                        "{\"amountMinor\":300}"))
                .toList());

        assertThat(results).extracting(Response::status).containsOnly(202, 422);
        assertThat(results).filteredOn(r -> r.status() == 202).hasSize(3);
        assertThat(((Number) JsonPath.read(api.payment(merchant, payment), "$.refunded.amountMinor")).longValue())
                .isEqualTo(900);
        assertThat(api.balance(merchant, wallet)).isEqualTo(100);
        assertBooksBalance();
    }

    @Test
    void webhooksDeliveredManyTimesAtOnceCreditOnce() throws Exception {
        UUID wallet = api.newWallet(merchant);
        UUID payment = api.startTopUp(merchant, wallet, 700, "pm_card_ok");
        ChargeResponse charge = api.awaitSubmitted(merchant, payment);
        provider.settle(charge);

        // The same event ten times (a provider retrying), plus ten different "succeeded" events
        String event = chargeEvent(newEventId(), charge, "succeeded", 700);
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> webhook(event));
            String other = chargeEvent(newEventId(), charge, "succeeded", 700);
            tasks.add(() -> webhook(other));
        }
        List<Response> results = concurrently(tasks);

        assertThat(results).extracting(Response::status).containsOnly(200);
        List<String> outcomes = results.stream().map(r -> (String) JsonPath.read(r.body(), "$.outcome")).toList();
        assertThat(outcomes).filteredOn("APPLIED"::equals).hasSize(1);
        assertThat(outcomes).isSubsetOf("APPLIED", "DUPLICATE", "IGNORED");
        assertThat(api.balance(merchant, wallet)).isEqualTo(700);
        assertBooksBalance();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Reconciliation finds nothing wrong with this merchant's books. */
    private void assertBooksBalance() throws Exception {
        String report = api.reconcile();
        assertThat(TestApi.findingsFor(report, merchant)).isEmpty();
        List<Map<String, Object>> positions = TestApi.positionsFor(report, merchant);
        assertThat(positions).isNotEmpty().allSatisfy(p -> assertThat(p.get("balanced")).isEqualTo(true));
    }

    /** Runs every task on its own thread, released together by a start gate, and waits for all. */
    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Response transfer(UUID from, UUID to, long amount, String idempotencyKey) throws IOException,
            InterruptedException {
        return post("/api/transfers", idempotencyKey, """
                {"fromWalletId":"%s","toWalletId":"%s","amountMinor":%d,"currency":"EUR"}""".formatted(from, to, amount));
    }

    private Response post(String path, String idempotencyKey, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + merchant.apiKey())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    private Response webhook(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/provider"))
                .header("Content-Type", "application/json")
                .header("Provider-Signature", TestApi.signature(body, System.currentTimeMillis() / 1000))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Idempotent-Replayed").orElse(null));
    }
}
