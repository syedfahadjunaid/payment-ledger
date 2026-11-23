package com.paymentledger.ledger;

import com.paymentledger.common.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that moves money. Everything else (transfers, top-ups, refunds) describes a
 * movement as a {@link PostingRequest} and hands it to {@link #post}.
 *
 * <p>Writes use {@code Propagation.MANDATORY}: they must run inside the caller's transaction, so
 * the money movement commits together with the business change it belongs to (a payment becoming
 * SUCCEEDED, an idempotency key being recorded), or not at all.
 */
@Service
@Transactional(readOnly = true)
public class LedgerService {

    private final EntityManager entityManager;
    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final JdbcClient jdbc;
    private final Clock clock;

    public LedgerService(EntityManager entityManager, LedgerAccountRepository accounts,
                         LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                         JdbcClient jdbc, Clock clock) {
        this.entityManager = entityManager;
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount openWalletAccount(UUID merchantId, String currency) {
        return accounts.save(new LedgerAccount(merchantId, AccountType.WALLET, currency, clock.instant()));
    }

    /**
     * The merchant's clearing or in-transit account for a currency, created on first use.
     *
     * <p>Two requests may try to create it at the same moment. {@code INSERT ... ON CONFLICT DO
     * NOTHING} makes that safe: the second insert waits for the first to commit, then does nothing,
     * and both read the same row. (Native SQL, so the merchant id is passed explicitly.)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount systemAccount(UUID merchantId, AccountType type, String currency) {
        if (type == AccountType.WALLET) {
            throw new IllegalArgumentException("wallet accounts are opened per wallet");
        }
        jdbc.sql("""
                        INSERT INTO ledger_accounts (id, merchant_id, type, currency, normal_balance, allow_negative, created_at)
                        VALUES (:id, :merchantId, :type, :currency, :normalBalance, :allowNegative, :createdAt)
                        ON CONFLICT (merchant_id, type, currency) WHERE type <> 'WALLET' DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("merchantId", merchantId)
                .param("type", type.name())
                .param("currency", currency)
                .param("normalBalance", type.normalBalance().name())
                .param("allowNegative", type.allowNegative())
                .param("createdAt", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        return accounts.findByMerchantIdAndTypeAndCurrency(merchantId, type, currency).orElseThrow();
    }

    /**
     * Records one balanced money movement, all-or-nothing.
     *
     * <ol>
     *   <li>Lock every account involved ({@code SELECT ... FOR UPDATE}), always in account-id order.
     *       Concurrent postings on the same wallet now run one after another (no lost update), and
     *       the fixed order means two postings can never wait for each other in a circle (deadlock).</li>
     *   <li>With the locks held, check that no account that must stay positive would go negative.
     *       This is the overdraft check; nobody can change the balance between check and write.</li>
     *   <li>Insert the transaction and its entries. Database triggers update the cached balances,
     *       and a deferred trigger verifies at COMMIT that the entries sum to zero.</li>
     * </ol>
     *
     * @throws InsufficientFundsException if an account would be overdrawn (nothing is written)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(PostingRequest request) {
        for (Map.Entry<UUID, Long> net : request.netAmountByAccount().entrySet()) { // sorted = lock order
            LedgerAccount account = lockAccount(net.getKey());
            if (!account.getMerchantId().equals(request.merchantId()) || !account.getCurrency().equals(request.currency())) {
                throw new IllegalArgumentException("account " + account.getId() + " is not a "
                        + request.currency() + " account of merchant " + request.merchantId());
            }
            long balanceAfter = Math.addExact(account.getBalanceMinor(),
                    account.getNormalBalance().balanceChange(net.getValue()));
            if (!account.isAllowNegative() && balanceAfter < 0) {
                throw new InsufficientFundsException(account.getId(), "insufficient funds: the balance is "
                        + Money.describe(account.getBalanceMinor(), account.getCurrency()));
            }
        }

        Instant now = clock.instant();
        LedgerTransaction transaction = transactions.save(new LedgerTransaction(request, now));
        for (Leg leg : request.legs()) {
            entries.save(new LedgerEntry(transaction, leg));
        }
        // Send the INSERTs now, so the balance triggers (and the no-overdraft CHECK) run inside this
        // method rather than at some later flush.
        entityManager.flush();
        return transaction;
    }

    /** Current balance of an account, read from the database (see the gotcha on {@link LedgerAccount}). */
    public long balanceOf(UUID accountId) {
        return accounts.balanceOf(accountId);
    }

    /**
     * {@code refresh} with a lock re-reads the row with {@code SELECT ... FOR UPDATE}, so we see the
     * latest committed balance, not whatever was loaded earlier in this transaction.
     */
    private LedgerAccount lockAccount(UUID accountId) {
        LedgerAccount account = entityManager.find(LedgerAccount.class, accountId);
        if (account == null) {
            throw new IllegalArgumentException("unknown ledger account " + accountId);
        }
        entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        return account;
    }
}
