package com.paymentledger.merchant;

import com.paymentledger.merchant.MerchantService.IssuedKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints (admin token). Thin controller: validation here, logic in the service. */
@RestController
@RequestMapping("/admin/merchants")
public class MerchantAdminController {

    public record CreateMerchantRequest(@NotBlank @Size(max = 200) String name) {}

    private final MerchantService merchants;

    public MerchantAdminController(MerchantService merchants) {
        this.merchants = merchants;
    }

    /** Creates a merchant and its first API key. The response is the only time the key is shown. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKey create(@Valid @RequestBody CreateMerchantRequest request) {
        return merchants.createMerchant(request.name());
    }

    /** An additional key, e.g. to rotate keys without downtime. */
    @PostMapping("/{merchantId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKey createApiKey(@PathVariable UUID merchantId) {
        return merchants.createApiKey(merchantId);
    }
}
