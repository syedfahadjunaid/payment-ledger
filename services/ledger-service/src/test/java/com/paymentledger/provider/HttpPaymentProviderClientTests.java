package com.paymentledger.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.config.LedgerProperties;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.CreateChargeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real HTTP client against a tiny JDK web server: headers, error mapping and timeouts.
 * The distinction that matters: a 4xx is a definite "no", anything else is "outcome unknown".
 */
class HttpPaymentProviderClientTests {

    private HttpServer server;
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile int status = 201;
    private volatile String body = "";
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTheApiKeyAndIdempotencyKey() {
        body = """
                {"id":"ch_1","reference":"r","status":"processing","amountMinor":500,"currency":"EUR","extra":"ignored"}""";

        ChargeResponse charge = client(Duration.ofSeconds(2)).createCharge(
                new CreateChargeRequest(500, "EUR", "pm_card_ok", "r"), "payment-123");

        assertThat(charge.id()).isEqualTo("ch_1");
        assertThat(lastHeaders).containsEntry("Authorization", "Bearer test-provider-key")
                .containsEntry("Idempotency-Key", "payment-123");
    }

    @Test
    void aClientErrorIsADefiniteRejection() {
        status = 402;
        body = "{\"error\":\"card_invalid\",\"message\":\"the card number is invalid\"}";

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).createCharge(
                new CreateChargeRequest(500, "EUR", "pm_card_invalid", "r"), "k"))
                .isInstanceOf(ProviderRejectedException.class)
                .hasMessage("card_invalid");
    }

    @Test
    void serverErrorsTimeoutsAndOutagesMeanOutcomeUnknown() {
        status = 503;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getCharge("ch_1"))
                .isInstanceOf(ProviderUnavailableException.class);

        status = 200;
        delayMillis = 1_500;
        assertThatThrownBy(() -> client(Duration.ofMillis(300)).getCharge("ch_1"))
                .isInstanceOf(ProviderUnavailableException.class);

        server.stop(0);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getCharge("ch_1"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    private HttpPaymentProviderClient client(Duration readTimeout) {
        LedgerProperties props = new LedgerProperties("x".repeat(32), List.of("EUR"), 1_000_000,
                new LedgerProperties.Provider("http://127.0.0.1:" + server.getAddress().getPort(), "test-provider-key",
                        "y".repeat(32), Duration.ofMinutes(5), Duration.ofSeconds(1), readTimeout),
                new LedgerProperties.Saga(Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(2)),
                new LedgerProperties.Reconciliation("0 15 0 * * *", "UTC"),
                new LedgerProperties.Idempotency(Duration.ofHours(24)));
        return new HttpPaymentProviderClient(RestClient.builder(), props, JsonMapper.builder().build());
    }

    private void handle(HttpExchange exchange) throws IOException {
        // (the JDK server normalises header names, so look them up rather than copying the map)
        for (String name : List.of("Authorization", "Idempotency-Key")) {
            lastHeaders.put(name, Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst(name), ""));
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
