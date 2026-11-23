package com.paymentledger.wallet;

import com.paymentledger.audit.AuditService;
import com.paymentledger.common.Amounts;
import com.paymentledger.common.ConflictException;
import com.paymentledger.common.Money;
import com.paymentledger.common.NotFoundException;
import com.paymentledger.ledger.LedgerAccount;
import com.paymentledger.ledger.LedgerService;
import com.paymentledger.ledger.TransactionType;
import com.paymentledger.wallet.WalletDtos.CreateWalletRequest;
import com.paymentledger.wallet.WalletDtos.StatementLine;
import com.paymentledger.wallet.WalletDtos.WalletView;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final AuditService audit;
    private final Amounts amounts;
    private final JdbcClient jdbc;
    private final Clock clock;

    public WalletService(WalletRepository wallets, LedgerService ledger, AuditService audit, Amounts amounts,
                         JdbcClient jdbc, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.audit = audit;
        this.amounts = amounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public WalletView create(UUID merchantId, CreateWalletRequest request) {
        amounts.requireSupportedCurrency(request.currency(), "currency");
        if (wallets.existsByMerchantIdAndCustomerRefAndCurrency(merchantId, request.customerRef(), request.currency())) {
            // (Two simultaneous creates both pass this check; the unique constraint then rejects one: 409.)
            throw new ConflictException("wallet_exists", "this customer already has a " + request.currency() + " wallet");
        }
        LedgerAccount account = ledger.openWalletAccount(merchantId, request.currency());
        Wallet wallet = wallets.save(new Wallet(merchantId, request.customerRef(), request.currency(), account.getId(),
                clock.instant()));
        audit.record(merchantId, "WALLET_CREATED", "wallet", wallet.getId(),
                request.customerRef() + " " + request.currency());
        return view(wallet, 0);
    }

    public WalletView get(UUID merchantId, UUID walletId) {
        Wallet wallet = require(merchantId, walletId);
        return view(wallet, ledger.balanceOf(wallet.getLedgerAccountId()));
    }

    /** The wallet, if it belongs to this merchant. Someone else's wallet is "not found" (404, not 403). */
    public Wallet require(UUID merchantId, UUID walletId) {
        return wallets.findByIdAndMerchantId(walletId, merchantId)
                .orElseThrow(() -> new NotFoundException("wallet " + walletId + " not found"));
    }

    /** Newest first. Plain SQL: a read-only report across two tables, with the merchant filter explicit. */
    public List<StatementLine> statement(UUID merchantId, UUID walletId, int limit) {
        Wallet wallet = require(merchantId, walletId);
        return jdbc.sql("""
                        SELECT t.id, t.type, t.reference_id, t.description, e.amount_minor, t.created_at
                          FROM ledger_entries e
                          JOIN ledger_transactions t ON t.id = e.transaction_id
                         WHERE e.account_id = :accountId AND e.merchant_id = :merchantId
                         ORDER BY t.created_at DESC, t.id DESC
                         LIMIT :limit""")
                .param("accountId", wallet.getLedgerAccountId())
                .param("merchantId", merchantId)
                .param("limit", limit)
                .query((rs, rowNum) -> new StatementLine(
                        rs.getObject("id", UUID.class),
                        TransactionType.valueOf(rs.getString("type")),
                        // credit-normal account: a credit (negative entry) is money in for the customer
                        Money.of(Math.negateExact(rs.getLong("amount_minor")), wallet.getCurrency()),
                        rs.getObject("reference_id", UUID.class),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static WalletView view(Wallet wallet, long balanceMinor) {
        return new WalletView(wallet.getId(), wallet.getCustomerRef(), wallet.getCurrency(),
                Money.of(balanceMinor, wallet.getCurrency()), wallet.getCreatedAt());
    }
}
