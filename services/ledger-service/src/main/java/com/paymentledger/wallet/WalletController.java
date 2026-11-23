package com.paymentledger.wallet;

import com.paymentledger.security.MerchantPrincipal;
import com.paymentledger.wallet.WalletDtos.CreateWalletRequest;
import com.paymentledger.wallet.WalletDtos.StatementLine;
import com.paymentledger.wallet.WalletDtos.WalletView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** Creating a wallet moves no money, so it needs no Idempotency-Key (a retry gets 409 wallet_exists). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WalletView create(@AuthenticationPrincipal MerchantPrincipal merchant,
                             @Valid @RequestBody CreateWalletRequest request) {
        return wallets.create(merchant.merchantId(), request);
    }

    @GetMapping("/{walletId}")
    public WalletView get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID walletId) {
        return wallets.get(merchant.merchantId(), walletId);
    }

    @GetMapping("/{walletId}/entries")
    public List<StatementLine> statement(@AuthenticationPrincipal MerchantPrincipal merchant,
                                         @PathVariable UUID walletId,
                                         @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return wallets.statement(merchant.merchantId(), walletId, limit);
    }
}
