package com.paymentledger.payment;

import java.util.UUID;

/**
 * One piece of news from the provider about a charge or refund, whatever channel it came by
 * (API response, webhook, or the recovery job asking). Every channel goes through the same code.
 *
 * @param providerId    the provider's id ("ch_..." / "re_..."); null if it never gave us one
 * @param reference     our id (payment or refund) that we sent the provider
 * @param amountMinor   what the provider says the amount is; must match ours
 * @param failureReason the provider's reason when {@code event} is FAILED
 */
public record ProviderUpdate(String providerId, UUID reference, ProviderEvent event, long amountMinor,
                             String currency, String failureReason) {}
