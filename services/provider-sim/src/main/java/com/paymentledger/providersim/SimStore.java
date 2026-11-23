package com.paymentledger.providersim;

import com.paymentledger.providersim.SimModels.Charge;
import com.paymentledger.providersim.SimModels.Refund;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * In-memory state. Every method is {@code synchronized}: the simulator is tiny, and one lock
 * makes "check the idempotency key, then create" and "check the refundable amount, then reserve
 * it" atomic without any cleverness.
 */
@Component
public class SimStore {

    /** The stored object, and whether this call created it (false = idempotent replay). */
    public record Created<T>(T value, boolean created) {}

    private final Map<String, Charge> charges = new HashMap<>();
    private final Map<String, Refund> refunds = new HashMap<>();
    private final Map<String, String> idempotencyKeys = new HashMap<>(); // "charge:<key>" -> charge id

    public synchronized Created<Charge> createCharge(String idempotencyKey, Supplier<Charge> factory) {
        String existing = idempotencyKeys.get("charge:" + idempotencyKey);
        if (existing != null) {
            return new Created<>(charges.get(existing), false);
        }
        Charge charge = factory.get();
        charges.put(charge.id(), charge);
        idempotencyKeys.put("charge:" + idempotencyKey, charge.id());
        return new Created<>(charge, true);
    }

    public synchronized Created<Refund> createRefund(String idempotencyKey, String chargeId, long amountMinor,
                                                     Supplier<Refund> factory) {
        String existing = idempotencyKeys.get("refund:" + idempotencyKey);
        if (existing != null) {
            return new Created<>(refunds.get(existing), false);
        }
        Charge charge = charges.get(chargeId);
        if (charge == null) {
            throw new SimException(HttpStatus.NOT_FOUND, "charge_not_found", "no charge " + chargeId);
        }
        if (!"succeeded".equals(charge.status())) {
            throw new SimException(HttpStatus.CONFLICT, "charge_not_settled", "charge is " + charge.status());
        }
        if (amountMinor > charge.amountMinor() - charge.refundedMinor()) {
            throw new SimException(HttpStatus.BAD_REQUEST, "amount_exceeds_charge",
                    "only " + (charge.amountMinor() - charge.refundedMinor()) + " left to refund");
        }
        charges.put(chargeId, charge.withRefunded(charge.refundedMinor() + amountMinor));
        Refund refund = factory.get();
        refunds.put(refund.id(), refund);
        idempotencyKeys.put("refund:" + idempotencyKey, refund.id());
        return new Created<>(refund, true);
    }

    public synchronized Charge updateCharge(Charge charge) {
        charges.put(charge.id(), charge);
        return charge;
    }

    /** A failed refund gives the amount back, so it can be refunded again. */
    public synchronized Refund updateRefund(Refund refund) {
        refunds.put(refund.id(), refund);
        if ("failed".equals(refund.status())) {
            Charge charge = charges.get(refund.chargeId());
            charges.put(charge.id(), charge.withRefunded(charge.refundedMinor() - refund.amountMinor()));
        }
        return refund;
    }

    public synchronized Optional<Charge> charge(String id) {
        return Optional.ofNullable(charges.get(id));
    }

    public synchronized Optional<Refund> refund(String id) {
        return Optional.ofNullable(refunds.get(id));
    }

    public synchronized Collection<Charge> allCharges() {
        return List.copyOf(charges.values());
    }

    public synchronized Collection<Refund> allRefunds() {
        return List.copyOf(refunds.values());
    }
}
