package com.paymentledger.ledger;

import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A request to record one balanced money movement. The constructor refuses anything that doesn't
 * balance, so a bug fails here, early and loudly. (The database checks it again at commit.)
 */
public record PostingRequest(
        UUID merchantId,
        TransactionType type,
        UUID referenceId,
        String currency,
        String description,
        List<Leg> legs) {

    public PostingRequest {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(currency, "currency");
        legs = List.copyOf(legs);
        if (legs.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two legs");
        }
        long sum = 0;
        for (Leg leg : legs) {
            sum = Math.addExact(sum, leg.amountMinor()); // overflow -> exception, never a wrong sum
        }
        if (sum != 0) {
            throw new IllegalArgumentException("debits and credits must balance; legs sum to " + sum);
        }
    }

    /**
     * Net amount per account, sorted by account id. The sort order is the lock order: every
     * posting locks its accounts in the same global order, so two postings can't deadlock.
     */
    SortedMap<UUID, Long> netAmountByAccount() {
        SortedMap<UUID, Long> net = new TreeMap<>();
        for (Leg leg : legs) {
            net.merge(leg.accountId(), leg.amountMinor(), Math::addExact);
        }
        return net;
    }
}
