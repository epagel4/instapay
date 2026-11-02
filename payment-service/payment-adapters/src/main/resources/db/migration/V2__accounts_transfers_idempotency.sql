CREATE TABLE account (
    id          UUID        PRIMARY KEY,
    institution CHAR(8)     NOT NULL,
    holder_name TEXT        NOT NULL,
    holder_cpf  CHAR(11)    NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    -- Aggregate version for optimistic locking (ADR-002). No balance column: see ledger.
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfer (
    id                   UUID        PRIMARY KEY,
    debtor_institution   CHAR(8)     NOT NULL,
    debtor_account       UUID        NOT NULL,
    creditor_institution CHAR(8)     NOT NULL,
    creditor_account     UUID        NOT NULL,
    amount_cents         BIGINT      NOT NULL CHECK (amount_cents > 0),
    status               TEXT        NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ
);

-- Stored HTTP responses for Idempotency-Key (ADR-003). Written in the same transaction as the
-- business effect, so a committed row always means "the operation happened exactly once".
-- Response columns are filled before COMMIT; they are only NULL inside the claiming transaction.
CREATE TABLE idempotency_record (
    key          TEXT        PRIMARY KEY,
    fingerprint  TEXT        NOT NULL,
    status_code  INT,
    content_type TEXT,
    body         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
