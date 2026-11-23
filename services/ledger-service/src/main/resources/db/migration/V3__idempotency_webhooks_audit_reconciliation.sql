-- Supporting tables: idempotency keys, the webhook inbox, the audit trail and reconciliation runs.

-- One row per (merchant, Idempotency-Key). The row is inserted FIRST, in the same database
-- transaction as the money movement, and the response is stored before COMMIT. So either the
-- money moved and the response is recorded, or neither happened. A concurrent retry with the same
-- key waits on the primary key until the first request commits, then replays its response.
CREATE TABLE idempotency_keys (
    merchant_id         UUID         NOT NULL REFERENCES merchants (id),
    idempotency_key     VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,   -- SHA-256 of method + path + body: same key, other request -> 422
    response_body       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_created ON idempotency_keys (created_at);

-- Webhook inbox: every provider event we accepted, exactly once (UNIQUE provider_event_id).
-- The raw body is kept as received (it is what the signature covers), for investigations.
CREATE TABLE webhook_events (
    id                  UUID         PRIMARY KEY,
    provider_event_id   VARCHAR(64)  NOT NULL UNIQUE,
    event_type          VARCHAR(64)  NOT NULL,
    payload             TEXT         NOT NULL,
    signature_timestamp TIMESTAMPTZ  NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    -- Filled in once, in the same transaction, after the event was processed
    outcome             VARCHAR(32),
    outcome_detail      VARCHAR(255),
    merchant_id         UUID         REFERENCES merchants (id)
);

-- Write-once: the processing result may be filled in once; nothing else may ever change.
CREATE FUNCTION webhook_events_write_once() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.outcome IS NULL
       AND (NEW.id, NEW.provider_event_id, NEW.event_type, NEW.payload, NEW.signature_timestamp, NEW.received_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.provider_event_id, OLD.event_type, OLD.payload, OLD.signature_timestamp, OLD.received_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'webhook_events rows are write-once (% is not allowed)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_webhook_events_write_once
    BEFORE UPDATE OR DELETE ON webhook_events
    FOR EACH ROW EXECUTE FUNCTION webhook_events_write_once();

-- Audit trail: who did what, when. Written in the same transaction as the change it describes.
CREATE TABLE audit_events (
    id          UUID         PRIMARY KEY,
    merchant_id UUID         REFERENCES merchants (id),   -- NULL for system-wide events
    actor       VARCHAR(100) NOT NULL,                    -- "api-key:sk_test_AbCd", "admin", "provider", "system"
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(64)  NOT NULL,
    entity_id   VARCHAR(64),
    details     VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_events_merchant_time ON audit_events (merchant_id, occurred_at DESC);

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

-- Reconciliation reports: one row per run, never changed afterwards.
CREATE TABLE reconciliation_runs (
    id             UUID         PRIMARY KEY,
    business_date  DATE         NOT NULL,
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('BALANCED', 'DISCREPANCIES', 'INCOMPLETE')),
    findings_count INTEGER      NOT NULL,
    triggered_by   VARCHAR(100) NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL,
    finished_at    TIMESTAMPTZ  NOT NULL,
    report         JSONB        NOT NULL
);

CREATE INDEX ix_reconciliation_runs_finished ON reconciliation_runs (finished_at DESC);

CREATE TRIGGER trg_reconciliation_runs_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO ${appRole};   -- DELETE: expiry job
GRANT SELECT, INSERT, UPDATE         ON webhook_events TO ${appRole};
GRANT SELECT, INSERT                 ON audit_events, reconciliation_runs TO ${appRole};
