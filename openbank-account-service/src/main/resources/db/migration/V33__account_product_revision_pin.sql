-- SPDX-License-Identifier: Apache-2.0
-- ADR-0341 D1 / ADR-0352 phase 3: an account records the PUBLISHED catalog revision it was opened
-- under. The pin is the contract's single source of terms; billing, interest and later consumers
-- read it from here (and from the AccountCreated event) instead of resolving their own.
--
-- All columns are nullable: accounts opened before this migration have no truthful pin and are
-- deliberately NOT backfilled here. The backfill (the revision effective at opened_at, never the
-- one published when the backfill runs) is a separate, reviewed follow-up; see the service docs.
--
-- Rollback: earlier binaries never read or write these columns, so they can run against this
-- schema unchanged. Dropping the columns is only safe once nothing reads the pin; drop the trigger
-- and function first.

ALTER TABLE accounts
    ADD COLUMN product_revision_id           UUID,
    ADD COLUMN product_revision_number       BIGINT,
    ADD COLUMN product_offering_id           UUID,
    ADD COLUMN product_revision_product_code VARCHAR(64),
    ADD COLUMN product_revision_content_hash VARCHAR(64),
    ADD COLUMN product_revision_ir_hash      VARCHAR(64),
    ADD COLUMN product_revision_ir_version   INTEGER,
    ADD COLUMN product_revision_pinned_at    TIMESTAMPTZ,
    -- A pin is whole or absent: never a revision id without the number and time it was taken.
    ADD CONSTRAINT chk_accounts_product_revision_pin_whole CHECK (
        (product_revision_id IS NULL AND product_revision_number IS NULL AND product_offering_id IS NULL
            AND product_revision_product_code IS NULL AND product_revision_content_hash IS NULL
            AND product_revision_ir_hash IS NULL AND product_revision_ir_version IS NULL
            AND product_revision_pinned_at IS NULL)
        OR
        (product_revision_id IS NOT NULL AND product_revision_number IS NOT NULL
            AND product_revision_number >= 1 AND product_offering_id IS NOT NULL
            AND product_revision_product_code IS NOT NULL AND product_revision_pinned_at IS NOT NULL)
    ),
    -- The IR hash and its version travel together: a revision either carries signed IR or none.
    ADD CONSTRAINT chk_accounts_product_revision_ir CHECK (
        (product_revision_ir_hash IS NULL) = (product_revision_ir_version IS NULL)
    ),
    ADD CONSTRAINT chk_accounts_product_revision_hashes CHECK (
        (product_revision_content_hash IS NULL OR product_revision_content_hash ~ '^[0-9a-f]{64}$')
        AND (product_revision_ir_hash IS NULL OR product_revision_ir_hash ~ '^[0-9a-f]{64}$')
    );

-- Immutable once set (ADR-0341: a change of terms is a new revision plus an explicit, audited re-pin,
-- never an edit). Setting a pin on an unpinned row stays possible, which is what the documented
-- backfill will need; changing or clearing an existing one is refused here, whatever the caller.
CREATE FUNCTION accounts_product_revision_pin_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.product_revision_id IS NOT NULL AND (
        NEW.product_revision_id IS DISTINCT FROM OLD.product_revision_id
        OR NEW.product_revision_number IS DISTINCT FROM OLD.product_revision_number
        OR NEW.product_offering_id IS DISTINCT FROM OLD.product_offering_id
        OR NEW.product_revision_product_code IS DISTINCT FROM OLD.product_revision_product_code
        OR NEW.product_revision_content_hash IS DISTINCT FROM OLD.product_revision_content_hash
        OR NEW.product_revision_ir_hash IS DISTINCT FROM OLD.product_revision_ir_hash
        OR NEW.product_revision_ir_version IS DISTINCT FROM OLD.product_revision_ir_version
        OR NEW.product_revision_pinned_at IS DISTINCT FROM OLD.product_revision_pinned_at
    ) THEN
        RAISE EXCEPTION 'account % product revision pin is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_product_revision_pin_immutable
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_product_revision_pin_immutable();

-- Serves the unpinned-accounts gauge (openbank.account.product_revision.unpinned) without a scan.
CREATE INDEX idx_accounts_unpinned_product_revision
    ON accounts (status)
    WHERE product_revision_id IS NULL;
