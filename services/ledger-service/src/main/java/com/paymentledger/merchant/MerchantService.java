package com.paymentledger.merchant;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.NotFoundException;
import com.paymentledger.security.ApiKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onboarding: merchants and their API keys. Operator-only (see MerchantAdminController). */
@Service
@Transactional(readOnly = true)
public class MerchantService {

    /** The plain key appears here exactly once; afterwards only its hash exists. */
    public record IssuedKey(UUID merchantId, String merchantName, UUID apiKeyId, String apiKey, String keyPrefix,
                            Instant createdAt) {}

    private final MerchantRepository merchants;
    private final ApiKeyRepository apiKeys;
    private final AuditService audit;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, ApiKeyRepository apiKeys, AuditService audit, Clock clock) {
        this.merchants = merchants;
        this.apiKeys = apiKeys;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IssuedKey createMerchant(String name) {
        Merchant merchant = merchants.save(new Merchant(name, clock.instant()));
        audit.record(merchant.getId(), "MERCHANT_CREATED", "merchant", merchant.getId(), name);
        return issueKey(merchant);
    }

    @Transactional
    public IssuedKey createApiKey(UUID merchantId) {
        Merchant merchant = merchants.findById(merchantId).orElseThrow(() -> new NotFoundException("merchant not found"));
        return issueKey(merchant);
    }

    private IssuedKey issueKey(Merchant merchant) {
        String plainKey = ApiKeys.generate();
        ApiKey key = apiKeys.save(new ApiKey(merchant.getId(), ApiKeys.sha256Hex(plainKey),
                ApiKeys.displayPrefix(plainKey), clock.instant()));
        audit.record(merchant.getId(), "API_KEY_CREATED", "api_key", key.getId(), key.getKeyPrefix());
        return new IssuedKey(merchant.getId(), merchant.getName(), key.getId(), plainKey, key.getKeyPrefix(),
                key.getCreatedAt());
    }
}
