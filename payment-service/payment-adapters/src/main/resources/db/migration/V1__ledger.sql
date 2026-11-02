-- Double-entry ledger. Both tables are append-only; corrections are new, compensating entries.

CREATE TABLE journal_entry (
    id          UUID        PRIMARY KEY,
    type        TEXT        NOT NULL CHECK (type IN ('DEPOSIT', 'TRANSFER')),
    reference   UUID        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX journal_entry_reference_idx ON journal_entry (reference);

-- amount_cents is signed: negative = debit (leaves the account), positive = credit.
CREATE TABLE posting (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id UUID   NOT NULL REFERENCES journal_entry (id),
    account_id       UUID   NOT NULL,
    amount_cents     BIGINT NOT NULL CHECK (amount_cents <> 0),
    UNIQUE (journal_entry_id, account_id)
);

-- Balance = SUM(amount_cents) by account; INCLUDE allows an index-only scan.
CREATE INDEX posting_account_idx ON posting (account_id) INCLUDE (amount_cents);

-- Zero-sum invariant, checked at COMMIT (deferred) so all postings of an entry are visible.
CREATE FUNCTION assert_journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    entry_id UUID;
    lines    INT;
    total    NUMERIC;
BEGIN
    -- IF, not CASE: plpgsql resolves NEW fields even in CASE branches that are not taken.
    IF TG_TABLE_NAME = 'journal_entry' THEN
        entry_id := NEW.id;
    ELSE
        entry_id := NEW.journal_entry_id;
    END IF;
    SELECT count(*), coalesce(sum(amount_cents), 0) INTO lines, total
      FROM posting WHERE journal_entry_id = entry_id;
    IF lines < 2 OR total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (% postings, sum %)', entry_id, lines, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_balanced();

-- Append-only: no UPDATE, DELETE or TRUNCATE, whatever the application does.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER journal_entry_no_truncate BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER posting_no_truncate BEFORE TRUNCATE ON posting
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
