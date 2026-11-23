package com.paymentledger.providersim;

import com.paymentledger.providersim.SimModels.Event;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers webhooks "at least once", like real providers do: an event is retried with
 * exponential backoff (1 s, 2 s, 4 s, ...) until the receiver answers 2xx or attempts run out.
 * Each attempt is signed again with a fresh timestamp, but keeps the same event id, which is
 * what the receiver uses to drop duplicates.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final ScheduledExecutorService scheduler;
    private final JsonMapper json;
    private final SimProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public WebhookSender(ScheduledExecutorService simScheduler, JsonMapper json, SimProperties props, Clock clock) {
        this.scheduler = simScheduler;
        this.json = json;
        this.props = props;
        this.clock = clock;
    }

    /** Delivers after {@code delay}, retrying on failure. Returns immediately. */
    public void send(Event event, Duration delay) {
        scheduler.schedule(() -> deliver(event, 1), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void deliver(Event event, int attempt) {
        byte[] body = json.writeValueAsBytes(event);
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.webhookUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(Signature.HEADER, Signature.header(props.webhookSecret(), clock.instant().getEpochSecond(), body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        int status;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            status = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (status >= 200 && status < 300) {
            log.info("Delivered {} {} (attempt {})", event.type(), event.id(), attempt);
            return;
        }
        if (attempt >= props.maxDeliveryAttempts()) {
            log.warn("Giving up on {} {} after {} attempts (last status {})", event.type(), event.id(), attempt, status);
            return;
        }
        long backoffSeconds = 1L << (attempt - 1);
        log.info("Delivery of {} {} failed (status {}); retrying in {} s", event.type(), event.id(), status, backoffSeconds);
        scheduler.schedule(() -> deliver(event, attempt + 1), backoffSeconds, TimeUnit.SECONDS);
    }
}
