-- One decision per transfer (the primary key makes redeliveries idempotent).
CREATE TABLE settlement (
    transfer_id      UUID        PRIMARY KEY,
    debtor_ispb      CHAR(8)     NOT NULL,
    creditor_ispb    CHAR(8)     NOT NULL,
    creditor_account UUID        NOT NULL,
    amount_cents     BIGINT      NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('SETTLED', 'REJECTED')),
    reason           TEXT,
    decided_at       TIMESTAMPTZ NOT NULL
);
