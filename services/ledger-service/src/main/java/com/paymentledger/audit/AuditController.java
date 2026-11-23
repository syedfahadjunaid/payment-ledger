package com.paymentledger.audit;

import com.paymentledger.security.MerchantPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A merchant's own audit trail, newest first. */
@RestController
public class AuditController {

    public record AuditEventView(UUID id, String actor, String action, String entityType, String entityId,
                                 String details, Instant occurredAt) {}

    private final AuditEventRepository events;

    public AuditController(AuditEventRepository events) {
        this.events = events;
    }

    @GetMapping("/api/audit-events")
    @Transactional(readOnly = true)
    public List<AuditEventView> list(@AuthenticationPrincipal MerchantPrincipal merchant,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return events.findByMerchantIdOrderByOccurredAtDesc(merchant.merchantId(), PageRequest.ofSize(limit)).stream()
                .map(e -> new AuditEventView(e.getId(), e.getActor(), e.getAction(), e.getEntityType(), e.getEntityId(),
                        e.getDetails(), e.getOccurredAt()))
                .toList();
    }
}
