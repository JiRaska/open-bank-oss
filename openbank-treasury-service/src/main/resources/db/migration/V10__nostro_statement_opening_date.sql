-- SPDX-License-Identifier: Apache-2.0
-- Nostro reconciliation (#11113): the OPBD balance's date, so the ledger opening balance, the
-- closing balance and the lines are read over one period — (opening_date, statement_date].
--
-- A row stored before this column existed is backfilled with the day before its earliest booking
-- date (or statement date), which is exactly the date the reconciliation read its opening balance
-- at until now, so no stored statement changes meaning.
--
-- Rollback: additive only. ALTER TABLE nostro_statements DROP COLUMN opening_date;

ALTER TABLE nostro_statements ADD COLUMN opening_date DATE;

UPDATE nostro_statements s
SET opening_date = LEAST(
        s.statement_date,
        COALESCE((SELECT MIN(e.booking_date) FROM nostro_statement_entries e
                  WHERE e.statement_uuid = s.statement_uuid), s.statement_date)
    ) - 1;

ALTER TABLE nostro_statements ALTER COLUMN opening_date SET NOT NULL;
