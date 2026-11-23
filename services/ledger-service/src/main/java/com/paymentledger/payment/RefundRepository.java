package com.paymentledger.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /** Just the payment id, without loading (and caching) the refund before we lock it. */
    @Query("select r.paymentId from Refund r where r.id = :id")
    Optional<UUID> findPaymentIdById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(UUID id);

    List<Refund> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ProcessingStatus status, Instant before);
}
