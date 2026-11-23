package com.paymentledger.wallet;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Merchant-facing lookups always include the merchant id from the API key. */
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByIdAndMerchantId(UUID id, UUID merchantId);

    boolean existsByMerchantIdAndCustomerRefAndCurrency(UUID merchantId, String customerRef, String currency);
}
