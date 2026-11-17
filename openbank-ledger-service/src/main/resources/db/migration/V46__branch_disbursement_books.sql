-- SPDX-License-Identifier: Apache-2.0
-- ADR-0380 B2: an immutable establishment dimension of the bank's journal.
-- Rollback: stop branch writers and retain the register, entries and idempotency evidence.
CREATE TABLE ledger_branch_books (
    establishment_id UUID PRIMARY KEY,
    bank_id UUID NOT NULL,
    host_country VARCHAR(2) NOT NULL CHECK (host_country ~ '^[A-Z]{2}$' AND host_country <> 'CZ')
);

ALTER TABLE journal_entries ADD COLUMN branch_book_id UUID REFERENCES ledger_branch_books(establishment_id);
CREATE INDEX journal_entries_branch_book_date_idx ON journal_entries (branch_book_id, entry_date)
    WHERE branch_book_id IS NOT NULL;

CREATE FUNCTION refuse_branch_book_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.branch_book_id IS DISTINCT FROM OLD.branch_book_id THEN
        RAISE EXCEPTION 'Journal branch book cannot be changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entries_keep_branch_book
BEFORE UPDATE OF branch_book_id ON journal_entries
FOR EACH ROW EXECUTE FUNCTION refuse_branch_book_change();
