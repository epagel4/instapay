ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_check;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_check
    CHECK (type IN ('DEPOSIT', 'TRANSFER', 'EXTERNAL_TRANSFER', 'SETTLEMENT', 'COMPENSATION', 'REFUND'));

-- Limit usage = SUM over the debtor's transfers in a time window.
CREATE INDEX transfer_debtor_created_idx ON transfer (debtor_account, created_at) INCLUDE (amount_cents, status);

-- Refunds (devolução) are immutable; their sum per transfer never exceeds the original amount
-- (enforced under a lock on the original transfer row).
CREATE TABLE refund (
    id                   UUID        PRIMARY KEY,
    original_transfer_id UUID        NOT NULL REFERENCES transfer (id),
    amount_cents         BIGINT      NOT NULL CHECK (amount_cents > 0),
    reason               TEXT,
    created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX refund_original_idx ON refund (original_transfer_id);

CREATE TRIGGER refund_append_only BEFORE UPDATE OR DELETE ON refund
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- Per-account overrides; no row means the institution defaults apply.
CREATE TABLE account_limits (
    account_id            UUID        PRIMARY KEY REFERENCES account (id),
    per_transaction_cents BIGINT      NOT NULL CHECK (per_transaction_cents > 0),
    daily_cents           BIGINT      NOT NULL CHECK (daily_cents > 0),
    nightly_cents         BIGINT      NOT NULL CHECK (nightly_cents > 0),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
