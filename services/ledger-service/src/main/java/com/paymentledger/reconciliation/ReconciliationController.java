package com.paymentledger.reconciliation;

import com.paymentledger.reconciliation.ReconciliationService.RunSummary;
import com.paymentledger.security.CurrentActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints (admin token): run reconciliation now, and read past reports. */
@RestController
@RequestMapping("/admin/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final Clock clock;

    public ReconciliationController(ReconciliationService reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    /** {@code POST /admin/reconciliations?date=2026-11-22}; without a date: today (UTC) so far. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationReport run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate date) {
        return reconciliation.run(date != null ? date : LocalDate.now(clock), CurrentActor.name());
    }

    @GetMapping
    public List<RunSummary> latest(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return reconciliation.latest(limit);
    }

    @GetMapping("/{runId}")
    public ReconciliationReport get(@PathVariable UUID runId) {
        return reconciliation.get(runId);
    }
}
