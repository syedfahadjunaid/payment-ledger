package com.paymentledger.security;

import java.util.UUID;

/**
 * Who is calling: the merchant behind a valid API key. Controllers receive it with
 * {@code @AuthenticationPrincipal}, and pass {@code merchantId} into every query, so a merchant
 * can only ever see and move its own money. The merchant never comes from the request body.
 */
public record MerchantPrincipal(UUID merchantId, UUID apiKeyId, String keyPrefix) implements Actor {

    @Override
    public String actorName() {
        return "api-key:" + keyPrefix;
    }
}
