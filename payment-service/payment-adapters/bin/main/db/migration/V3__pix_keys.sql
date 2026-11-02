-- Payment keys (DICT). The primary key is the uniqueness rule: one account per key, globally.
CREATE TABLE pix_key (
    type       TEXT        NOT NULL CHECK (type IN ('CPF', 'EMAIL', 'PHONE', 'RANDOM')),
    value      TEXT        NOT NULL,
    account_id UUID        NOT NULL REFERENCES account (id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (type, value)
);

CREATE INDEX pix_key_account_idx ON pix_key (account_id);

-- Portability / ownership claims. No FK to pix_key: resolved claims outlive deleted keys.
CREATE TABLE key_claim (
    id                  UUID        PRIMARY KEY,
    key_type            TEXT        NOT NULL,
    key_value           TEXT        NOT NULL,
    claim_type          TEXT        NOT NULL CHECK (claim_type IN ('PORTABILITY', 'OWNERSHIP')),
    donor_account       UUID        NOT NULL REFERENCES account (id),
    claimer_account     UUID        NOT NULL REFERENCES account (id),
    status              TEXT        NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    opened_at           TIMESTAMPTZ NOT NULL,
    resolution_deadline TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ
);

-- At most one open claim per key, even under concurrent requests.
CREATE UNIQUE INDEX key_claim_one_open_per_key ON key_claim (key_type, key_value) WHERE status = 'OPEN';
