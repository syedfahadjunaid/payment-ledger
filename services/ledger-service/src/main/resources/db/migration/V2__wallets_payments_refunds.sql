-- Business objects that move money through the ledger.
-- Composite foreign keys with (merchant_id, id, currency) make the database reject a payment in
-- EUR for a USD wallet, or a transfer between two merchants' wallets.

CREATE TABLE wallets (
    id                UUID         PRIMARY KEY,
    merchant_id       UUID         NOT NULL REFERENCES merchants (id),
    customer_ref      VARCHAR(100) NOT NULL,   -- the merchant's own id for the customer
    currency          VARCHAR(3)   NOT NULL,
    ledger_account_id UUID         NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_wallets_merchant_id_currency UNIQUE (merchant_id, id, currency),
    CONSTRAINT uq_wallets_customer_currency UNIQUE (merchant_id, customer_ref, currency),
    CONSTRAINT fk_wallets_account FOREIGN KEY (merchant_id, ledger_account_id, currency)
        REFERENCES ledger_accounts (merchant_id, id, currency)
);

-- A wallet-to-wallet transfer: one synchronous ACID transaction, append-only.
CREATE TABLE transfers (
    id                    UUID         PRIMARY KEY,
    merchant_id           UUID         NOT NULL,
    source_wallet_id      UUID         NOT NULL,
    destination_wallet_id UUID         NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
    description           VARCHAR(200),
    ledger_transaction_id UUID         NOT NULL UNIQUE,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_transfers_two_wallets CHECK (source_wallet_id <> destination_wallet_id),
    CONSTRAINT fk_transfers_source FOREIGN KEY (merchant_id, source_wallet_id, currency)
        REFERENCES wallets (merchant_id, id, currency),
    CONSTRAINT fk_transfers_destination FOREIGN KEY (merchant_id, destination_wallet_id, currency)
        REFERENCES wallets (merchant_id, id, currency),
    CONSTRAINT fk_transfers_ledger FOREIGN KEY (merchant_id, ledger_transaction_id, currency)
        REFERENCES ledger_transactions (merchant_id, id, currency)
);

CREATE TRIGGER trg_transfers_append_only
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- A top-up paid by card through the payment provider (a saga, see PaymentSaga).
--   CREATED    -> saved, provider not called yet
--   PROCESSING -> the provider accepted the charge; waiting for its webhook
--   SUCCEEDED  -> money credited to the wallet (exactly one TOP_UP ledger transaction)
--   FAILED     -> declined; no money moved
CREATE TABLE payments (
    id                 UUID         PRIMARY KEY,
    merchant_id        UUID         NOT NULL,
    wallet_id          UUID         NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    -- Refunds reserved or done. Pending refunds count too, so two refunds can't both pass the check.
    refunded_minor     BIGINT       NOT NULL DEFAULT 0,
    payment_method     VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    provider_charge_id VARCHAR(64)  UNIQUE,
    failure_reason     VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    succeeded_at       TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking (@Version)
    CONSTRAINT uq_payments_merchant_id_currency UNIQUE (merchant_id, id, currency),
    CONSTRAINT fk_payments_wallet FOREIGN KEY (merchant_id, wallet_id, currency)
        REFERENCES wallets (merchant_id, id, currency),
    CONSTRAINT ck_payments_refunds_within_amount CHECK (refunded_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT ck_payments_refunds_need_success CHECK (refunded_minor = 0 OR status = 'SUCCEEDED')
);

CREATE INDEX ix_payments_status_updated ON payments (status, updated_at);
CREATE INDEX ix_payments_succeeded ON payments (succeeded_at) WHERE status = 'SUCCEEDED';

-- A refund of (part of) a top-up, back to the card. Also a saga, with a compensating step:
--   CREATED    -> money moved from the wallet to "refunds in transit"; provider not called yet
--   PROCESSING -> the provider accepted the refund
--   SUCCEEDED  -> money left for the card (REFUND_SETTLE)
--   FAILED     -> the provider refused; the reservation was reversed back into the wallet
CREATE TABLE refunds (
    id                 UUID         PRIMARY KEY,
    merchant_id        UUID         NOT NULL,
    payment_id         UUID         NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    reason             VARCHAR(200),
    provider_refund_id VARCHAR(64)  UNIQUE,
    failure_reason     VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_refunds_payment FOREIGN KEY (merchant_id, payment_id, currency)
        REFERENCES payments (merchant_id, id, currency)
);

CREATE INDEX ix_refunds_payment ON refunds (payment_id);
CREATE INDEX ix_refunds_status_updated ON refunds (status, updated_at);

GRANT SELECT, INSERT         ON wallets, transfers TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON payments, refunds TO ${appRole};
