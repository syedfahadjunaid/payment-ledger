package com.paymentledger.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByMerchantIdAndTypeAndCurrency(UUID merchantId, AccountType type, String currency);

    /** A scalar query always reads the database, never a stale object from the persistence context. */
    @Query("select a.balanceMinor from LedgerAccount a where a.id = :id")
    long balanceOf(UUID id);
}
