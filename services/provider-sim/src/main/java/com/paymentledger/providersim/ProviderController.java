package com.paymentledger.providersim;

import com.paymentledger.providersim.SimModels.Charge;
import com.paymentledger.providersim.SimModels.CreateChargeRequest;
import com.paymentledger.providersim.SimModels.CreateRefundRequest;
import com.paymentledger.providersim.SimModels.Refund;
import com.paymentledger.providersim.SimModels.SettlementReport;
import com.paymentledger.providersim.SimStore.Created;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The provider's REST API. Every /v1 call needs {@code Authorization: Bearer <api key>} (see SimConfig). */
@RestController
@RequestMapping("/v1")
public class ProviderController {

    private final Simulator simulator;
    private final SimStore store;

    public ProviderController(Simulator simulator, SimStore store) {
        this.simulator = simulator;
        this.store = store;
    }

    /** 201 for a new charge, 200 when the Idempotency-Key was seen before (same charge returned). */
    @PostMapping("/charges")
    public ResponseEntity<Charge> createCharge(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               @RequestBody CreateChargeRequest request) {
        Created<Charge> created = simulator.createCharge(request, key);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.value());
    }

    @GetMapping("/charges/{id}")
    public Charge charge(@PathVariable String id) {
        return store.charge(id).orElseThrow(() -> new SimException(HttpStatus.NOT_FOUND, "not_found", "no charge " + id));
    }

    @PostMapping("/refunds")
    public ResponseEntity<Refund> createRefund(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               @RequestBody CreateRefundRequest request) {
        Created<Refund> created = simulator.createRefund(request, key);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.value());
    }

    @GetMapping("/refunds/{id}")
    public Refund refund(@PathVariable String id) {
        return store.refund(id).orElseThrow(() -> new SimException(HttpStatus.NOT_FOUND, "not_found", "no refund " + id));
    }

    @GetMapping("/settlements")
    public SettlementReport settlements(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return simulator.settlementReport(date);
    }

    @ExceptionHandler(SimException.class)
    ResponseEntity<Map<String, String>> error(SimException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getCode(), "message", e.getMessage()));
    }
}
