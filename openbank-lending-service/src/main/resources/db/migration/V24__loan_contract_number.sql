-- SPDX-License-Identifier: Apache-2.0
-- Human-readable, immutable loan contract number (#11107): UV-<origination year>-<per-year
-- sequence, zero-padded to at least six digits>, e.g. UV-2026-000123. The loan's UUID stays the
-- technical identity; this is the reference risk officers, the console and statements show.
--
-- Generation lives in the database so it is unique under concurrent disbursement without any
-- application-side coordination: next_loan_contract_number(year) upserts one counter row per
-- year, and the ON CONFLICT DO UPDATE row lock serialises concurrent callers for that year. The
-- counter is advanced in the creating transaction, so a rolled-back loan returns its number; the
-- scheme is gap-TOLERANT, not gap-free — nothing downstream may assume consecutive numbers.
-- The repository calls the function before INSERT so the domain sees the number it was given;
-- the BEFORE INSERT trigger is the backstop for any INSERT that does not (raw SQL seeds, tools).
-- A BEFORE UPDATE trigger makes the number immutable at the database, not only in the mapper.
--
-- Backfill is deterministic: per UTC year of created_at, ordered by (created_at, id), so a rerun
-- against the same rows assigns the same numbers; the counters then continue after the backfill.
--
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_loan_contract_number_immutable ON loan;
--   DROP TRIGGER IF EXISTS trg_loan_contract_number_assign ON loan;
--   DROP FUNCTION IF EXISTS loan_contract_number_immutable();
--   DROP FUNCTION IF EXISTS loan_contract_number_assign();
--   ALTER TABLE loan DROP COLUMN IF EXISTS contract_number;
--   DROP FUNCTION IF EXISTS next_loan_contract_number(INT);
--   DROP FUNCTION IF EXISTS format_loan_contract_number(INT, BIGINT);
--   DROP TABLE IF EXISTS loan_contract_number_counter;
-- Numbers already shown to customers or exported cannot be recalled by a rollback.

CREATE TABLE loan_contract_number_counter (
    year        INT    PRIMARY KEY CHECK (year BETWEEN 1000 AND 9999),
    last_value  BIGINT NOT NULL CHECK (last_value > 0)
);

-- lpad() TRUNCATES a longer string, so the sequence part is only padded, never cut.
CREATE FUNCTION format_loan_contract_number(p_year INT, p_seq BIGINT) RETURNS VARCHAR
    LANGUAGE sql IMMUTABLE AS
$$
    SELECT 'UV-' || p_year::text || '-' ||
           CASE WHEN length(p_seq::text) >= 6 THEN p_seq::text ELSE lpad(p_seq::text, 6, '0') END
$$;

CREATE FUNCTION next_loan_contract_number(p_year INT) RETURNS VARCHAR
    LANGUAGE plpgsql AS
$$
DECLARE
    v_seq BIGINT;
BEGIN
    INSERT INTO loan_contract_number_counter (year, last_value) VALUES (p_year, 1)
    ON CONFLICT (year) DO UPDATE SET last_value = loan_contract_number_counter.last_value + 1
    RETURNING last_value INTO v_seq;
    RETURN format_loan_contract_number(p_year, v_seq);
END
$$;

ALTER TABLE loan ADD COLUMN contract_number VARCHAR(32);

WITH numbered AS (
    SELECT id,
           EXTRACT(YEAR FROM created_at AT TIME ZONE 'UTC')::INT AS y,
           row_number() OVER (
               PARTITION BY EXTRACT(YEAR FROM created_at AT TIME ZONE 'UTC')
               ORDER BY created_at, id
           ) AS n
    FROM loan
)
UPDATE loan l
SET contract_number = format_loan_contract_number(numbered.y, numbered.n)
FROM numbered
WHERE l.id = numbered.id;

INSERT INTO loan_contract_number_counter (year, last_value)
SELECT EXTRACT(YEAR FROM created_at AT TIME ZONE 'UTC')::INT, count(*)
FROM loan
GROUP BY 1;

ALTER TABLE loan ALTER COLUMN contract_number SET NOT NULL;
ALTER TABLE loan ADD CONSTRAINT uq_loan_contract_number UNIQUE (contract_number);
ALTER TABLE loan ADD CONSTRAINT ck_loan_contract_number_format
    CHECK (contract_number ~ '^UV-[0-9]{4}-[0-9]{6,}$');

CREATE FUNCTION loan_contract_number_assign() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.contract_number IS NULL THEN
        NEW.contract_number := next_loan_contract_number(
            EXTRACT(YEAR FROM COALESCE(NEW.created_at, now()) AT TIME ZONE 'UTC')::INT
        );
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_loan_contract_number_assign
    BEFORE INSERT ON loan
    FOR EACH ROW EXECUTE FUNCTION loan_contract_number_assign();

CREATE FUNCTION loan_contract_number_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.contract_number IS DISTINCT FROM OLD.contract_number THEN
        RAISE EXCEPTION 'loan % contract_number is immutable (% -> %)',
            OLD.id, OLD.contract_number, NEW.contract_number
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_loan_contract_number_immutable
    BEFORE UPDATE OF contract_number ON loan
    FOR EACH ROW EXECUTE FUNCTION loan_contract_number_immutable();
