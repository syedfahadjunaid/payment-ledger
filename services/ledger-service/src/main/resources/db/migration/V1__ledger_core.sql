-- Flyway migration: runs once, in order, and is recorded in ledger.flyway_schema_history.
-- Never edit a migration that has already run anywhere. Add V4__..., V5__... instead.
--
-- Conventions in this schema
--   * Money is BIGINT minor units (cents, yen) plus a 3-letter ISO 4217 currency. No floats.
--   * Every merchant-owned row carries merchant_id, and rows link to each other with composite
--     foreign keys that include merchant_id (and currency where it matters), so the database
--     itself rejects a link across merchants or across currencies.
--   * ${appRole} is the login the running service uses. Each table grants it only what it needs.
--     On ledger history that is SELECT and INSERT: even a bug cannot UPDATE or DELETE it.
--   * Functions are created with "SET search_path FROM CURRENT" so they always resolve tables in
--     this schema, whatever search_path the caller has.

CREATE TABLE merchants (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

-- API keys are stored as SHA-256 hashes; the key itself is shown once, at creation.
CREATE TABLE api_keys (
    id          UUID        PRIMARY KEY,
    merchant_id UUID        NOT NULL REFERENCES merchants (id),
    key_hash    VARCHAR(64) NOT NULL UNIQUE,
    key_prefix  VARCHAR(16) NOT NULL,          -- e.g. "sk_test_AbCd": safe for logs and the audit trail
    created_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);

-- ---------------------------------------------------------------------------------------------
-- The double-entry ledger
--
--   ledger_accounts      one per wallet, plus a few system accounts per merchant and currency
--   ledger_transactions  one per business event (a top-up, a transfer, a refund step)
--   ledger_entries       two or more per transaction; signed amounts: + debit, - credit
--
-- Invariants enforced below by the database, not just by the Java code:
--   1. every transaction's entries sum to zero (checked at COMMIT by a deferred constraint trigger)
--   2. every transaction has at least two entries
--   3. an entry has the same merchant and currency as its transaction and its account
--   4. transactions and entries are append-only; a committed transaction is sealed
--   5. account balances change only through entries, and never go below zero
-- ---------------------------------------------------------------------------------------------

CREATE TABLE ledger_accounts (
    id             UUID        PRIMARY KEY,
    merchant_id    UUID        NOT NULL REFERENCES merchants (id),
    type           VARCHAR(32) NOT NULL CHECK (type IN ('WALLET', 'PROVIDER_CLEARING', 'REFUNDS_IN_TRANSIT')),
    currency       VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Which side increases the balance: DEBIT for assets (money we hold at the provider),
    -- CREDIT for liabilities (money we owe customers).
    normal_balance VARCHAR(6)  NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    allow_negative BOOLEAN     NOT NULL,
    -- Cached balance, in the account's normal direction. Maintained by trg_ledger_entries_apply_balance
    -- and checked against the entries by the reconciliation job.
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ledger_accounts_merchant_id_currency UNIQUE (merchant_id, id, currency),
    -- Last line of defence against overdrafts. The service checks first (and answers 422);
    -- this makes an overdraft impossible even if that check had a bug.
    CONSTRAINT ck_ledger_accounts_no_overdraft CHECK (allow_negative OR balance_minor >= 0)
);

-- At most one clearing / in-transit account per merchant and currency (wallets have one each)
CREATE UNIQUE INDEX uq_ledger_accounts_system ON ledger_accounts (merchant_id, type, currency)
    WHERE type <> 'WALLET';

CREATE TABLE ledger_transactions (
    id           UUID         PRIMARY KEY,
    merchant_id  UUID         NOT NULL REFERENCES merchants (id),
    type         VARCHAR(32)  NOT NULL
        CHECK (type IN ('TOP_UP', 'TRANSFER', 'REFUND_RESERVE', 'REFUND_SETTLE', 'REFUND_REVERSAL')),
    currency     VARCHAR(3)   NOT NULL,
    reference_id UUID         NOT NULL,   -- the payment, refund or transfer this money movement belongs to
    description  VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL,
    -- The database transaction that inserted this row. Entries may only be added by that same
    -- database transaction, so once committed a ledger transaction is sealed.
    created_xid  XID8         NOT NULL DEFAULT pg_current_xact_id(),
    CONSTRAINT uq_ledger_transactions_merchant_id_currency UNIQUE (merchant_id, id, currency)
);

CREATE INDEX ix_ledger_transactions_reference ON ledger_transactions (reference_id);

CREATE TABLE ledger_entries (
    id             UUID        PRIMARY KEY,
    merchant_id    UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor <> 0),   -- + debit, - credit
    created_at     TIMESTAMPTZ NOT NULL,
    -- Same merchant and currency as the transaction AND as the account, enforced by the keys
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (merchant_id, transaction_id, currency)
        REFERENCES ledger_transactions (merchant_id, id, currency),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (merchant_id, account_id, currency)
        REFERENCES ledger_accounts (merchant_id, id, currency)
);

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_id, created_at);

-- ---- invariant 4: history is append-only ----------------------------------------------------

CREATE FUNCTION forbid_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% is not allowed)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_ledger_transactions_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

-- A committed ledger transaction is sealed: no adding a balancing pair to it later.
CREATE FUNCTION ledger_entry_must_join_open_transaction() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    IF (SELECT created_xid FROM ledger_transactions WHERE id = NEW.transaction_id)
           IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION 'ledger transaction % is already committed; entries can only be added when it is created',
            NEW.transaction_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_entries_sealed
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_must_join_open_transaction();

-- ---- invariant 5: balances follow the entries -----------------------------------------------

CREATE FUNCTION ledger_entry_apply_balance() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    -- A debit raises a DEBIT-normal account and lowers a CREDIT-normal one, and vice versa.
    -- The row is already locked by the service (SELECT ... FOR UPDATE), so this cannot lose an update.
    UPDATE ledger_accounts
       SET balance_minor = balance_minor
                         + CASE normal_balance WHEN 'DEBIT' THEN NEW.amount_minor ELSE -NEW.amount_minor END
     WHERE id = NEW.account_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_ledger_entries_apply_balance
    AFTER INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_apply_balance();

-- Accounts never change, except their balance, and that only from the trigger above.
-- pg_trigger_depth() is 1 for a direct UPDATE and 2 when the UPDATE runs inside the entry trigger.
CREATE FUNCTION ledger_accounts_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger accounts cannot be deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.merchant_id, NEW.type, NEW.currency, NEW.normal_balance, NEW.allow_negative, NEW.created_at)
           IS DISTINCT FROM
       (OLD.id, OLD.merchant_id, OLD.type, OLD.currency, OLD.normal_balance, OLD.allow_negative, OLD.created_at) THEN
        RAISE EXCEPTION 'ledger account % is immutable', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.balance_minor IS DISTINCT FROM OLD.balance_minor AND pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'balances change only by posting ledger entries (account %)', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_accounts_guard
    BEFORE UPDATE OR DELETE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_accounts_guard();

-- ---- invariants 1 and 2: every transaction balances ------------------------------------------
--
-- A transaction's entries are inserted one by one, so after the first entry it is (briefly) out
-- of balance. The check therefore runs at COMMIT: a DEFERRABLE INITIALLY DEFERRED constraint
-- trigger. If the entries don't sum to zero, the whole database transaction is rolled back.

CREATE FUNCTION assert_ledger_transaction_balanced(tx_id UUID) RETURNS void
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
DECLARE
    entry_count INTEGER;
    total       NUMERIC;   -- NUMERIC so a sum of large BIGINTs cannot overflow while checking
BEGIN
    SELECT count(*), coalesce(sum(amount_minor), 0)
      INTO entry_count, total
      FROM ledger_entries
     WHERE transaction_id = tx_id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'ledger transaction % has % entries; at least 2 are required', tx_id, entry_count
            USING ERRCODE = 'check_violation';
    END IF;
    IF total <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % does not balance: its entries sum to % instead of 0', tx_id, total
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION ledger_entry_check_balanced() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    PERFORM assert_ledger_transaction_balanced(NEW.transaction_id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION ledger_transaction_check_balanced() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    PERFORM assert_ledger_transaction_balanced(NEW.id);   -- catches a transaction with no entries
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_check_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_check_balanced();

-- ---- privileges for the running service ------------------------------------------------------

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT SELECT, INSERT         ON merchants, ledger_transactions, ledger_entries TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON api_keys TO ${appRole};   -- UPDATE: revoking a key
-- UPDATE is needed for row locks (SELECT ... FOR UPDATE) and for the balance trigger, which runs
-- as the caller. trg_ledger_accounts_guard still rejects any direct change to an account.
GRANT SELECT, INSERT, UPDATE ON ledger_accounts TO ${appRole};
