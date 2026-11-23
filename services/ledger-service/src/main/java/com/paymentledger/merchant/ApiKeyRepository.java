package com.paymentledger.merchant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** Spring Data derives the SQL from the method name: key_hash = ? and revoked_at is null. */
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
}
