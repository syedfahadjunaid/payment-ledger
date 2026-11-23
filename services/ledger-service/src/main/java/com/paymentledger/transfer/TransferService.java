package com.paymentledger.transfer;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.Amounts;
import com.paymentledger.common.BusinessRuleException;
import com.paymentledger.common.Money;
import com.paymentledger.common.NotFoundException;
import com.paymentledger.ledger.LedgerService;
import com.paymentledger.ledger.LedgerTransaction;
import com.paymentledger.ledger.Leg;
import com.paymentledger.ledger.PostingRequest;
import com.paymentledger.ledger.TransactionType;
import com.paymentledger.transfer.TransferDtos.TransferRequest;
import com.paymentledger.transfer.TransferDtos.TransferView;
import com.paymentledger.wallet.Wallet;
import com.paymentledger.wallet.WalletService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wallet-to-wallet transfers: one synchronous ACID transaction, no external calls. */
@Service
@Transactional(readOnly = true)
public class TransferService {

    private final TransferRepository transfers;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final AuditService audit;
    private final Amounts amounts;
    private final EntityManager entityManager;
    private final Clock clock;

    public TransferService(TransferRepository transfers, WalletService wallets, LedgerService ledger,
                           AuditService audit, Amounts amounts, EntityManager entityManager, Clock clock) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.ledger = ledger;
        this.audit = audit;
        this.amounts = amounts;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Runs inside the idempotency transaction (REQUIRED joins it). */
    @Transactional
    public TransferView transfer(UUID merchantId, TransferRequest request) {
        if (request.fromWalletId().equals(request.toWalletId())) {
            throw new BusinessRuleException("same_wallet", "source and destination must be different wallets", "toWalletId");
        }
        Wallet from = wallets.require(merchantId, request.fromWalletId());
        Wallet to = wallets.require(merchantId, request.toWalletId());
        if (!from.getCurrency().equals(request.currency()) || !to.getCurrency().equals(request.currency())) {
            throw new BusinessRuleException("currency_mismatch",
                    "both wallets must hold " + request.currency() + " (no currency conversion)", "currency");
        }
        long amount = request.amountMinor();
        amounts.requireWithinLimit(amount, request.currency(), "amountMinor");

        // The ledger locks both wallet accounts, checks the source has enough, and writes the entries.
        UUID transferId = UUID.randomUUID();
        LedgerTransaction posted = ledger.post(new PostingRequest(merchantId, TransactionType.TRANSFER, transferId,
                request.currency(), request.description(),
                List.of(Leg.debit(from.getLedgerAccountId(), amount), Leg.credit(to.getLedgerAccountId(), amount))));

        Transfer transfer = new Transfer(transferId, merchantId, from.getId(), to.getId(), request.currency(), amount,
                request.description(), posted.getId(), clock.instant());
        entityManager.persist(transfer); // see the class comment on Transfer: persist, not save
        audit.record(merchantId, "TRANSFER_COMPLETED", "transfer", transferId,
                Money.describe(amount, request.currency()) + " from wallet " + from.getId() + " to wallet " + to.getId());
        return TransferView.of(transfer);
    }

    public TransferView get(UUID merchantId, UUID transferId) {
        return transfers.findByIdAndMerchantId(transferId, merchantId)
                .map(TransferView::of)
                .orElseThrow(() -> new NotFoundException("transfer " + transferId + " not found"));
    }
}
