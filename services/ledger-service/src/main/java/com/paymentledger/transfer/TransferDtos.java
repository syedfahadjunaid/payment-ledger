package com.paymentledger.transfer;

import com.paymentledger.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {}

    /**
     * The currency is repeated on purpose: the client states what it believes it is moving, and a
     * mismatch with the wallets is rejected instead of silently moving yen as if they were euros.
     */
    public record TransferRequest(
            @NotNull UUID fromWalletId,
            @NotNull UUID toWalletId,
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
            @Size(max = 200) String description) {}

    public record TransferView(UUID id, UUID fromWalletId, UUID toWalletId, Money amount, String description,
                               UUID ledgerTransactionId, Instant createdAt) {

        static TransferView of(Transfer transfer) {
            return new TransferView(transfer.getId(), transfer.getSourceWalletId(), transfer.getDestinationWalletId(),
                    Money.of(transfer.getAmountMinor(), transfer.getCurrency()), transfer.getDescription(),
                    transfer.getLedgerTransactionId(), transfer.getCreatedAt());
        }
    }
}
