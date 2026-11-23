package com.paymentledger.transfer;

import com.paymentledger.idempotency.IdempotencyService;
import com.paymentledger.security.MerchantPrincipal;
import com.paymentledger.transfer.TransferDtos.TransferRequest;
import com.paymentledger.transfer.TransferDtos.TransferView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferController(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    /** Moves money between two of the merchant's wallets. Requires an Idempotency-Key (400 without). */
    @PostMapping
    public ResponseEntity<TransferView> create(@AuthenticationPrincipal MerchantPrincipal merchant,
                                               @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
                                               @Valid @RequestBody TransferRequest request,
                                               HttpServletRequest http) {
        return idempotency.execute(merchant.merchantId(), idempotencyKey, idempotency.fingerprint(http, request),
                        TransferView.class, () -> transfers.transfer(merchant.merchantId(), request))
                .toResponse(HttpStatus.CREATED);
    }

    @GetMapping("/{transferId}")
    public TransferView get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID transferId) {
        return transfers.get(merchant.merchantId(), transferId);
    }
}
