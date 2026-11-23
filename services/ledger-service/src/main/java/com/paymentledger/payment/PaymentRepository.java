package com.paymentledger.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * {@code SELECT ... FOR UPDATE}: other transactions that want this row wait until we commit.
     *
     * <p>Gotcha: if the payment was already loaded earlier in the same transaction, Hibernate returns
     * that (possibly stale) object instead of the fresh row. So lock it as the first thing you do.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.merchantId = :merchantId")
    Optional<Payment> findByIdAndMerchantIdForUpdate(UUID id, UUID merchantId);

    /** For the recovery job: oldest first, a batch at a time. */
    List<Payment> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ProcessingStatus status, Instant before);
}
