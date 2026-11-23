package com.paymentledger.audit;

import com.paymentledger.security.CurrentActor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail. The ledger already records every money movement; this records the
 * decisions around it (who asked for a refund, which webhook was ignored and why).
 */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 500;

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * {@code MANDATORY}: must be called inside the caller's transaction, so the audit record and
     * the change it describes are committed together, or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID merchantId, String action, String entityType, Object entityId, String details) {
        recordAs(CurrentActor.name(), merchantId, action, entityType, entityId, details);
    }

    /** Same, naming the actor explicitly (e.g. "provider" for a webhook, which has no API key). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(String actor, UUID merchantId, String action, String entityType, Object entityId,
                         String details) {
        String trimmed = details == null || details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
        events.save(new AuditEvent(merchantId, actor, action, entityType,
                entityId == null ? null : String.valueOf(entityId), trimmed, clock.instant()));
    }
}
