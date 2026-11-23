package com.paymentledger;

import com.paymentledger.provider.PaymentProviderClient;
import com.paymentledger.provider.ProviderDtos.ChargeResponse;
import com.paymentledger.provider.ProviderDtos.CreateChargeRequest;
import com.paymentledger.provider.ProviderDtos.CreateRefundRequest;
import com.paymentledger.provider.ProviderDtos.RefundResponse;
import com.paymentledger.provider.ProviderDtos.SettledItem;
import com.paymentledger.provider.ProviderDtos.SettlementReport;
import com.paymentledger.provider.ProviderRejectedException;
import com.paymentledger.provider.ProviderUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * An in-memory payment provider for tests. Behaves like the real one where it matters
 * (idempotency keys, definite vs. unknown failures) and lets a test control the rest:
 * <ul>
 *   <li>{@code pm_card_invalid}: rejected synchronously; {@code pm_card_slow}: the HTTP answer is
 *       held back until {@link #releaseSlowCharges()}; {@code pm_card_norefund}: refunds are rejected;</li>
 *   <li>{@link #setAvailable(boolean)}: simulate an outage (calls fail with "outcome unknown");</li>
 *   <li>webhooks are not sent by the fake: tests deliver them, signed, through the real endpoint.</li>
 * </ul>
 */
public class FakePaymentProvider implements PaymentProviderClient {

    private final Map<String, ChargeResponse> chargesByKey = new ConcurrentHashMap<>();
    private final Map<String, ChargeResponse> chargesById = new ConcurrentHashMap<>();
    private final Map<UUID, ChargeResponse> chargesByPayment = new ConcurrentHashMap<>();
    private final Map<String, String> paymentMethodByCharge = new ConcurrentHashMap<>();
    private final Map<String, RefundResponse> refundsByKey = new ConcurrentHashMap<>();
    private final Map<String, RefundResponse> refundsById = new ConcurrentHashMap<>();
    private final Map<UUID, RefundResponse> refundsByRefund = new ConcurrentHashMap<>();
    private final Map<String, String> keyByProviderId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();
    private final List<SettledItem> settledCharges = new CopyOnWriteArrayList<>();
    private final List<SettledItem> settledRefunds = new CopyOnWriteArrayList<>();
    private final Set<String> succeededCharges = ConcurrentHashMap.newKeySet();
    private volatile boolean available = true;
    private volatile CountDownLatch slowGate = new CountDownLatch(0);

    // ---- PaymentProviderClient ----------------------------------------------------------------

    @Override
    public ChargeResponse createCharge(CreateChargeRequest request, String idempotencyKey) {
        attemptsByKey.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        if (!available) {
            throw new ProviderUnavailableException("fake provider is down", null);
        }
        if ("pm_card_invalid".equals(request.paymentMethod())) {
            throw new ProviderRejectedException("card_invalid");
        }
        ChargeResponse charge = chargesByKey.computeIfAbsent(idempotencyKey, key -> {
            ChargeResponse created = new ChargeResponse("ch_" + UUID.randomUUID(), request.reference(), "processing",
                    request.amountMinor(), request.currency(), null);
            chargesById.put(created.id(), created);
            paymentMethodByCharge.put(created.id(), request.paymentMethod());
            keyByProviderId.put(created.id(), key);
            chargesByPayment.put(UUID.fromString(request.reference()), created);
            return created;
        });
        if ("pm_card_slow".equals(request.paymentMethod())) {
            await(slowGate);
        }
        return charge;
    }

    @Override
    public ChargeResponse getCharge(String chargeId) {
        ChargeResponse charge = Optional.ofNullable(chargesById.get(chargeId))
                .orElseThrow(() -> new ProviderRejectedException("not_found"));
        String status = succeededCharges.contains(chargeId) ? "succeeded" : charge.status();
        return new ChargeResponse(charge.id(), charge.reference(), status, charge.amountMinor(), charge.currency(), null);
    }

    @Override
    public RefundResponse createRefund(CreateRefundRequest request, String idempotencyKey) {
        attemptsByKey.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        if (!available) {
            throw new ProviderUnavailableException("fake provider is down", null);
        }
        ChargeResponse charge = chargesById.get(request.chargeId());
        if (charge == null) {
            throw new ProviderRejectedException("charge_not_found");
        }
        if ("pm_card_norefund".equals(paymentMethodByCharge.get(charge.id()))) {
            throw new ProviderRejectedException("charge_not_refundable");
        }
        return refundsByKey.computeIfAbsent(idempotencyKey, key -> {
            RefundResponse created = new RefundResponse("re_" + UUID.randomUUID(), charge.id(), request.reference(),
                    "processing", request.amountMinor(), charge.currency(), null);
            refundsById.put(created.id(), created);
            keyByProviderId.put(created.id(), key);
            refundsByRefund.put(UUID.fromString(request.reference()), created);
            return created;
        });
    }

    @Override
    public RefundResponse getRefund(String refundId) {
        return Optional.ofNullable(refundsById.get(refundId)).orElseThrow(() -> new ProviderRejectedException("not_found"));
    }

    @Override
    public SettlementReport settlementReport(LocalDate date) {
        return new SettlementReport(date, onDate(settledCharges, date), onDate(settledRefunds, date));
    }

    // ---- test controls ------------------------------------------------------------------------

    /** The charge the saga created for this payment, once it has called us. */
    public ChargeResponse awaitCharge(UUID paymentId) {
        return awaitValue(() -> chargesByPayment.get(paymentId), "a charge for payment " + paymentId);
    }

    public RefundResponse awaitRefund(UUID refundId) {
        return awaitValue(() -> refundsByRefund.get(refundId), "a refund for " + refundId);
    }

    /** How often the saga called us with this idempotency key. */
    public int attempts(String idempotencyKey) {
        AtomicInteger attempts = attemptsByKey.get(idempotencyKey);
        return attempts == null ? 0 : attempts.get();
    }

    public String idempotencyKeyOf(String providerId) {
        return keyByProviderId.get(providerId);
    }

    /** Puts the charge in today's settlement report (what a real provider does once it succeeds). */
    public void settle(ChargeResponse charge) {
        succeededCharges.add(charge.id());
        settledCharges.add(new SettledItem(charge.id(), charge.reference(), charge.amountMinor(), charge.currency(),
                Instant.now()));
    }

    public void settle(RefundResponse refund) {
        settledRefunds.add(new SettledItem(refund.id(), refund.reference(), refund.amountMinor(), refund.currency(),
                Instant.now()));
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void holdSlowCharges() {
        slowGate = new CountDownLatch(1);
    }

    public void releaseSlowCharges() {
        slowGate.countDown();
    }

    private static List<SettledItem> onDate(List<SettledItem> items, LocalDate date) {
        return items.stream().filter(i -> LocalDate.ofInstant(i.settledAt(), ZoneOffset.UTC).equals(date)).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("slow charge was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T awaitValue(Supplier<T> lookup, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            T value = lookup.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
