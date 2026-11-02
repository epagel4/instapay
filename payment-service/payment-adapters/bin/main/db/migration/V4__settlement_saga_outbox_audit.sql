ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_check;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_check
    CHECK (type IN ('DEPOSIT', 'TRANSFER', 'EXTERNAL_TRANSFER', 'SETTLEMENT', 'COMPENSATION'));

-- Persisted orchestration state of transfers to other institutions (ADR-005).
CREATE TABLE settlement_saga (
    transfer_id           UUID        PRIMARY KEY REFERENCES transfer (id),
    state                 TEXT        NOT NULL CHECK (state IN ('AWAITING_SETTLEMENT', 'COMPLETED', 'COMPENSATED')),
    started_at            TIMESTAMPTZ NOT NULL,
    settlement_expires_at TIMESTAMPTZ NOT NULL,
    compensate_after      TIMESTAMPTZ NOT NULL,
    reason                TEXT,
    finished_at           TIMESTAMPTZ
);

CREATE INDEX settlement_saga_overdue_idx ON settlement_saga (compensate_after)
    WHERE state = 'AWAITING_SETTLEMENT';

-- Transactional outbox (ADR-006): written with the business change, published by the relay.
CREATE TABLE outbox_event (
    id           UUID        PRIMARY KEY,
    topic        TEXT        NOT NULL,
    message_key  TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_event_pending_idx ON outbox_event (created_at) WHERE published_at IS NULL;

-- Immutable audit log of domain events.
CREATE TABLE audit_event (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_event_aggregate_idx ON audit_event (aggregate_id, id);

CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
