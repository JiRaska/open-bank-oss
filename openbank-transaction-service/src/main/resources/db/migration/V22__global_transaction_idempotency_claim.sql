-- A partitioned table cannot enforce uniqueness without its partition key. Claim each key in
-- an unpartitioned table so inserts from old and new application versions obey the same rule.
-- Keep claims for as long as their transactions can be replayed; do not remove a claim merely
-- because a yearly transaction partition is detached.
--
-- This migration blocks transaction writes while it checks and backfills existing rows. A
-- duplicate already booked in different partitions needs manual reconciliation: choosing one
-- here would hide a real money movement. The exception rolls back the entire migration.
-- Rollback: only after reverting all writers that rely on global uniqueness, drop the trigger,
-- function, and claim table. Dropping claims while writers are active permits duplicate bookings.
CREATE TABLE transaction_idempotency_claims (
    idempotency_key VARCHAR(100) NOT NULL,
    transaction_id UUID NOT NULL,
    booking_date DATE NOT NULL,
    CONSTRAINT uq_transaction_idempotency_claims_key UNIQUE (idempotency_key)
);

LOCK TABLE transactions IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM transactions GROUP BY idempotency_key HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'historical duplicate transaction idempotency keys require reconciliation before V22';
    END IF;
END
$$;

INSERT INTO transaction_idempotency_claims (idempotency_key, transaction_id, booking_date)
SELECT idempotency_key, id, booking_date FROM transactions;

CREATE FUNCTION claim_transaction_idempotency_key() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO transaction_idempotency_claims (idempotency_key, transaction_id, booking_date)
    VALUES (NEW.idempotency_key, NEW.id, NEW.booking_date);
    RETURN NEW;
END
$$;

CREATE TRIGGER claim_transaction_idempotency_key_before_insert
BEFORE INSERT ON transactions
FOR EACH ROW EXECUTE FUNCTION claim_transaction_idempotency_key();

-- Existing rows may change status, but moving a booking to another partition or changing its
-- key would leave a stale claim and permit a second booking under the old key.
CREATE FUNCTION guard_transaction_idempotency_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.booking_date IS DISTINCT FROM OLD.booking_date THEN
        RAISE EXCEPTION 'transaction idempotency key and booking date are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER guard_transaction_idempotency_identity_before_update
BEFORE UPDATE ON transactions
FOR EACH ROW EXECUTE FUNCTION guard_transaction_idempotency_identity();
