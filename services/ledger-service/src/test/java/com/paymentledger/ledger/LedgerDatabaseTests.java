package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.IntegrationTest;
import com.paymentledger.Superuser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger invariants are enforced by the database itself, not only by the Java code.
 * These tests bypass the service and write SQL directly (as the application's own database
 * login), the way a buggy future code path might, and check that Postgres says no.
 */
@IntegrationTest
class LedgerDatabaseTests {

    @Autowired
    JdbcClient jdbc;            // the application's login (ledger_app)

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    Superuser superuser;

    private UUID merchant;
    private UUID wallet;
    private UUID clearing;

    /** One leg of a raw posting: account and signed amount (+ debit, - credit). */
    private record Entry(UUID account, long amount) {}

    @BeforeEach
    void setUp() {
        merchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchants (id, name, created_at) VALUES (?, 'db test', now())").param(merchant).update();
        wallet = account("WALLET", "CREDIT", "EUR");
        clearing = account("PROVIDER_CLEARING", "DEBIT", "EUR");
    }

    @Test
    void aBalancedTransactionUpdatesBothBalances() {
        post("EUR", new Entry(clearing, 500), new Entry(wallet, -500)); // a top-up of 5.00

        assertThat(balance(clearing)).isEqualTo(500);
        assertThat(balance(wallet)).isEqualTo(500);
    }

    @Test
    void anUnbalancedTransactionIsRejectedAtCommit() {
        assertThatThrownBy(() -> post("EUR", new Entry(clearing, 500), new Entry(wallet, -400)))
                .hasStackTraceContaining("does not balance");

        assertThat(balance(wallet)).isZero(); // rolled back as a whole
    }

    @Test
    void aTransactionNeedsAtLeastTwoEntries() {
        assertThatThrownBy(() -> post("EUR", new Entry(clearing, 500)))
                .hasStackTraceContaining("at least 2 are required");
        assertThatThrownBy(() -> post("EUR"))
                .hasStackTraceContaining("has 0 entries");
    }

    @Test
    void aCommittedTransactionIsSealed() {
        UUID transaction = post("EUR", new Entry(clearing, 500), new Entry(wallet, -500));

        // Adding a balanced pair later would keep the sum at zero, so it needs its own rule
        assertThatThrownBy(() -> this.transaction.executeWithoutResult(status -> {
            entry(transaction, clearing, "EUR", 100);
            entry(transaction, wallet, "EUR", -100);
        })).hasStackTraceContaining("already committed");
    }

    @Test
    void historyCannotBeChangedOrDeleted() {
        post("EUR", new Entry(clearing, 500), new Entry(wallet, -500));

        // The application's login has no UPDATE or DELETE privilege on the ledger at all...
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount_minor = 1 WHERE merchant_id = ?")
                .param(merchant).update()).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_transactions WHERE merchant_id = ?")
                .param(merchant).update()).hasStackTraceContaining("permission denied");

        // ...and even the owner is stopped by the append-only triggers
        JdbcClient owner = superuser.jdbc();
        assertThatThrownBy(() -> owner.sql("UPDATE ledger.ledger_entries SET amount_minor = 1 WHERE merchant_id = ?")
                .param(merchant).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM ledger.ledger_entries WHERE merchant_id = ?")
                .param(merchant).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("TRUNCATE ledger.ledger_entries").update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void balancesCanOnlyChangeThroughEntries() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_accounts SET balance_minor = 1000000 WHERE id = ?")
                .param(wallet).update()).hasStackTraceContaining("balances change only by posting ledger entries");
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_accounts SET currency = 'USD' WHERE id = ?")
                .param(wallet).update()).hasStackTraceContaining("immutable");
    }

    @Test
    void aWalletCannotGoNegativeEvenIfTheCodeForgetsToCheck() {
        // Spend 1.00 from an empty wallet: debit the wallet (it shrinks), credit clearing
        assertThatThrownBy(() -> post("EUR", new Entry(wallet, 100), new Entry(clearing, -100)))
                .hasStackTraceContaining("ck_ledger_accounts_no_overdraft");
    }

    @Test
    void entriesMustMatchTheirAccountsCurrencyAndMerchant() {
        UUID dollars = account("WALLET", "CREDIT", "USD");
        assertThatThrownBy(() -> post("EUR", new Entry(clearing, 500), new Entry(dollars, -500)))
                .hasStackTraceContaining("fk_ledger_entries_account");

        UUID otherMerchant = UUID.randomUUID();
        jdbc.sql("INSERT INTO merchants (id, name, created_at) VALUES (?, 'other', now())").param(otherMerchant).update();
        UUID theirWallet = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger_accounts (id, merchant_id, type, currency, normal_balance, allow_negative, created_at)
                VALUES (?, ?, 'WALLET', 'EUR', 'CREDIT', false, now())""").params(theirWallet, otherMerchant).update();
        assertThatThrownBy(() -> post("EUR", new Entry(clearing, 500), new Entry(theirWallet, -500)))
                .hasStackTraceContaining("fk_ledger_entries_account");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private UUID account(String type, String normalBalance, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger_accounts (id, merchant_id, type, currency, normal_balance, allow_negative, created_at)
                VALUES (?, ?, ?, ?, ?, false, now())""")
                .params(id, merchant, type, currency, normalBalance)
                .update();
        return id;
    }

    /** Inserts a transaction and its entries in one database transaction, bypassing LedgerService. */
    private UUID post(String currency, Entry... entries) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO ledger_transactions (id, merchant_id, type, currency, reference_id, created_at)
                    VALUES (?, ?, 'TRANSFER', ?, ?, now())""")
                    .params(id, merchant, currency, UUID.randomUUID())
                    .update();
            for (Entry entry : List.of(entries)) {
                entry(id, entry.account(), currency, entry.amount());
            }
        });
        return id;
    }

    private void entry(UUID transaction, UUID account, String currency, long amount) {
        jdbc.sql("""
                INSERT INTO ledger_entries (id, merchant_id, transaction_id, account_id, currency, amount_minor, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())""")
                .params(UUID.randomUUID(), merchant, transaction, account, currency, amount)
                .update();
    }

    private long balance(UUID account) {
        return jdbc.sql("SELECT balance_minor FROM ledger_accounts WHERE id = ?").param(account).query(Long.class).single();
    }
}
