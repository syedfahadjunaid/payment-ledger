package com.paymentledger.webhook;

import com.paymentledger.common.BadRequestException;
import com.paymentledger.config.LedgerProperties;
import com.paymentledger.payment.PaymentSaga;
import com.paymentledger.payment.ProviderEvent;
import com.paymentledger.payment.ProviderUpdate;
import com.paymentledger.payment.RefundSaga;
import com.paymentledger.payment.UpdateOutcome;
import com.paymentledger.provider.ProviderDtos.WebhookEvent;
import com.paymentledger.provider.ProviderDtos.WebhookObject;
import com.paymentledger.provider.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives the provider's webhooks. Providers deliver "at least once": the same event can come
 * twice, a retry can overtake a newer event, and anyone on the internet can POST to the URL.
 *
 * <ol>
 *   <li><b>Authenticity</b>: verify the HMAC signature and timestamp before trusting a single byte.</li>
 *   <li><b>Exactly-once effect</b>: in one transaction, insert the event id into the inbox table
 *       (UNIQUE) and apply it. A second delivery of the same id hits the unique key and does
 *       nothing; two concurrent deliveries are serialized by Postgres on that key.</li>
 *   <li><b>Out-of-order</b>: the saga's state machine only moves forward, so an old "processing"
 *       event arriving after "succeeded" is recorded and ignored.</li>
 * </ol>
 * We answer 200 for anything we accepted (including duplicates and ignored events), so the
 * provider stops retrying. Only a bad signature (401) or a malformed body (400) is refused.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** What we tell the provider (and what the event row records). */
    public record WebhookResult(String eventId, String outcome, String detail) {}

    private final PaymentSaga paymentSaga;
    private final RefundSaga refundSaga;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final LedgerProperties.Provider settings;
    private final Clock clock;

    public WebhookService(PaymentSaga paymentSaga, RefundSaga refundSaga, JdbcClient jdbc, JsonMapper json,
                          TransactionTemplate transaction, LedgerProperties props, Clock clock) {
        this.paymentSaga = paymentSaga;
        this.refundSaga = refundSaga;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
        this.settings = props.provider();
        this.clock = clock;
    }

    public WebhookResult receive(String signatureHeader, byte[] body) {
        Instant signedAt = WebhookSignature.verify(settings.webhookSecret(), signatureHeader, body, clock.instant(),
                settings.webhookTolerance());
        WebhookEvent event = parse(body);
        return transaction.execute(status -> process(event, body, signedAt));
    }

    private WebhookResult process(WebhookEvent event, byte[] body, Instant signedAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO webhook_events (id, provider_event_id, event_type, payload, signature_timestamp, received_at)
                        VALUES (:id, :eventId, :type, :payload, :signedAt, :receivedAt)
                        ON CONFLICT (provider_event_id) DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("eventId", event.id())
                .param("type", event.type())
                .param("payload", new String(body, StandardCharsets.UTF_8))
                .param("signedAt", signedAt.atOffset(ZoneOffset.UTC))
                .param("receivedAt", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        if (inserted == 0) {
            log.info("Duplicate webhook {} ignored", event.id());
            return new WebhookResult(event.id(), "DUPLICATE", "already received");
        }

        UpdateOutcome outcome = dispatch(event);
        jdbc.sql("""
                        UPDATE webhook_events SET outcome = :outcome, outcome_detail = :detail, merchant_id = :merchantId
                         WHERE provider_event_id = :eventId""")
                .param("outcome", outcome.result().name())
                .param("detail", truncate(outcome.detail()))
                .param("merchantId", outcome.merchantId())
                .param("eventId", event.id())
                .update();
        log.info("Webhook {} ({}): {} {}", event.id(), event.type(), outcome.result(), outcome.detail());
        return new WebhookResult(event.id(), outcome.result().name(), outcome.detail());
    }

    private UpdateOutcome dispatch(WebhookEvent event) {
        WebhookObject data = event.data();
        Optional<ProviderEvent> providerEvent = ProviderEvent.fromProviderStatus(data.status());
        Optional<UUID> reference = parseUuid(data.reference());
        if (providerEvent.isEmpty()) {
            return UpdateOutcome.unsupported("unknown status '" + data.status() + "'");
        }
        if (reference.isEmpty()) {
            return UpdateOutcome.unknownReference("reference is not one of our ids");
        }
        ProviderUpdate update = new ProviderUpdate(data.id(), reference.get(), providerEvent.get(), data.amountMinor(),
                data.currency(), data.failureReason());
        return switch (String.valueOf(data.objectType())) {
            case "charge" -> paymentSaga.apply(update);
            case "refund" -> refundSaga.apply(update);
            default -> UpdateOutcome.unsupported("unknown object type '" + data.objectType() + "'");
        };
    }

    private WebhookEvent parse(byte[] body) {
        try {
            WebhookEvent event = json.readValue(body, WebhookEvent.class);
            if (event == null || event.id() == null || event.id().isBlank() || event.id().length() > 64
                    || event.type() == null || event.data() == null) {
                throw new BadRequestException("webhook body is missing id, type or data");
            }
            return event;
        } catch (JacksonException e) {
            throw new BadRequestException("webhook body is not valid JSON");
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
