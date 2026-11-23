package com.paymentledger.wallet;

import com.paymentledger.common.Money;
import com.paymentledger.ledger.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for /api/wallets. Records: immutable, and Jackson maps them directly. */
public final class WalletDtos {

    private WalletDtos() {}

    public record CreateWalletRequest(
            @NotBlank @Size(max = 100) String customerRef,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency) {}

    public record WalletView(UUID id, String customerRef, String currency, Money balance, Instant createdAt) {}

    /**
     * One line of a wallet statement, from the customer's point of view: money in is positive.
     * (In the ledger a wallet is credit-normal, so a credit entry, stored negative, is money in.)
     */
    public record StatementLine(UUID transactionId, TransactionType type, Money amount, UUID referenceId,
                                String description, Instant createdAt) {}
}
