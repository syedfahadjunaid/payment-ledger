package com.paymentledger.providersim;

import com.paymentledger.providersim.SimModels.Charge;
import com.paymentledger.providersim.SimModels.CreateChargeRequest;
import com.paymentledger.providersim.SimModels.CreateRefundRequest;
import com.paymentledger.providersim.SimModels.Event;
import com.paymentledger.providersim.SimModels.EventData;
import com.paymentledger.providersim.SimModels.Refund;
import com.paymentledger.providersim.SimModels.SettledItem;
import com.paymentledger.providersim.SimModels.SettlementReport;
import com.paymentledger.providersim.SimStore.Created;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The provider's behaviour. Test payment methods (like a real provider's test cards):
 * <ul>
 *   <li>{@code pm_card_ok}: the charge succeeds</li>
 *   <li>{@code pm_card_declined}: the charge fails later (webhook {@code charge.failed})</li>
 *   <li>{@code pm_card_invalid}: rejected immediately, HTTP 402</li>
 *   <li>{@code pm_card_chaos}: succeeds, but the final webhook is delivered twice and a stale
 *       {@code charge.processing} arrives after it (duplicates + out-of-order delivery)</li>
 *   <li>{@code pm_card_refund_fails}: the charge succeeds, refunds of it fail</li>
 * </ul>
 */
@Service
public class Simulator {

    static final Set<String> PAYMENT_METHODS =
            Set.of("pm_card_ok", "pm_card_declined", "pm_card_invalid", "pm_card_chaos", "pm_card_refund_fails");

    private final SimStore store;
    private final WebhookSender webhooks;
    private final ScheduledExecutorService scheduler;
    private final Duration settleDelay;
    private final Clock clock;

    public Simulator(SimStore store, WebhookSender webhooks, ScheduledExecutorService simScheduler, SimProperties props,
                     Clock clock) {
        this.store = store;
        this.webhooks = webhooks;
        this.scheduler = simScheduler;
        this.settleDelay = props.settleDelay();
        this.clock = clock;
    }

    public Created<Charge> createCharge(CreateChargeRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (request.amountMinor() == null || request.amountMinor() <= 0) {
            throw new SimException(HttpStatus.BAD_REQUEST, "invalid_amount", "amountMinor must be a positive integer");
        }
        if (request.currency() == null || !request.currency().matches("[A-Z]{3}")) {
            throw new SimException(HttpStatus.BAD_REQUEST, "invalid_currency", "currency must be an ISO 4217 code");
        }
        if (!PAYMENT_METHODS.contains(request.paymentMethod())) {
            throw new SimException(HttpStatus.BAD_REQUEST, "unknown_payment_method", "try one of " + PAYMENT_METHODS);
        }
        if (request.paymentMethod().equals("pm_card_invalid")) {
            throw new SimException(HttpStatus.PAYMENT_REQUIRED, "card_invalid", "the card number is invalid");
        }
        Created<Charge> created = store.createCharge(idempotencyKey, () -> new Charge(newId("ch_"), request.reference(),
                request.amountMinor(), request.currency(), request.paymentMethod(), "processing", 0, null,
                clock.instant(), null));
        if (created.created()) {
            scheduleOutcome(created.value());
        }
        return created;
    }

    public Created<Refund> createRefund(CreateRefundRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (request.amountMinor() == null || request.amountMinor() <= 0) {
            throw new SimException(HttpStatus.BAD_REQUEST, "invalid_amount", "amountMinor must be a positive integer");
        }
        if (request.chargeId() == null) {
            throw new SimException(HttpStatus.BAD_REQUEST, "missing_charge", "chargeId is required");
        }
        Created<Refund> created = store.createRefund(idempotencyKey, request.chargeId(), request.amountMinor(),
                () -> new Refund(newId("re_"), request.chargeId(), request.reference(), request.amountMinor(),
                        store.charge(request.chargeId()).orElseThrow().currency(), "processing", null, clock.instant(),
                        null));
        if (created.created()) {
            scheduleOutcome(created.value());
        }
        return created;
    }

    /** What the provider says it settled on {@code date} (UTC): the input to the ledger's reconciliation. */
    public SettlementReport settlementReport(LocalDate date) {
        Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plus(Duration.ofDays(1));
        List<SettledItem> charges = store.allCharges().stream()
                .filter(c -> "succeeded".equals(c.status()) && inRange(c.settledAt(), from, to))
                .sorted(Comparator.comparing(Charge::settledAt))
                .map(c -> new SettledItem(c.id(), c.reference(), c.amountMinor(), c.currency(), c.settledAt()))
                .toList();
        List<SettledItem> refunds = store.allRefunds().stream()
                .filter(r -> "succeeded".equals(r.status()) && inRange(r.settledAt(), from, to))
                .sorted(Comparator.comparing(Refund::settledAt))
                .map(r -> new SettledItem(r.id(), r.reference(), r.amountMinor(), r.currency(), r.settledAt()))
                .toList();
        return new SettlementReport(date, charges, refunds);
    }

    private void scheduleOutcome(Charge charge) {
        boolean chaos = charge.paymentMethod().equals("pm_card_chaos");
        Event processing = event("charge.processing", EventData.of(charge));
        if (!chaos) {
            webhooks.send(processing, settleDelay.dividedBy(4));
        }
        scheduler.schedule(() -> {
            boolean declined = charge.paymentMethod().equals("pm_card_declined");
            Charge done = store.updateCharge(declined
                    ? charge.settled("failed", "card_declined", clock.instant())
                    : charge.settled("succeeded", null, clock.instant()));
            Event outcome = event(declined ? "charge.failed" : "charge.succeeded", EventData.of(done));
            webhooks.send(outcome, Duration.ZERO);
            if (chaos) {
                webhooks.send(outcome, Duration.ofMillis(300));     // the same event again: a duplicate
                webhooks.send(processing, Duration.ofMillis(600));  // an older event, arriving last
            }
        }, settleDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleOutcome(Refund refund) {
        scheduler.schedule(() -> {
            boolean fails = store.charge(refund.chargeId()).map(Charge::paymentMethod)
                    .filter("pm_card_refund_fails"::equals).isPresent();
            Refund done = store.updateRefund(fails
                    ? refund.settled("failed", "refund_rejected_by_issuer", clock.instant())
                    : refund.settled("succeeded", null, clock.instant()));
            webhooks.send(event(fails ? "refund.failed" : "refund.succeeded", EventData.of(done)), Duration.ZERO);
        }, settleDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Event event(String type, EventData data) {
        return new Event(newId("evt_"), type, clock.instant(), data);
    }

    private static boolean inRange(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new SimException(HttpStatus.BAD_REQUEST, "missing_idempotency_key", "Idempotency-Key header is required");
        }
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
