-- Global protection across booking-date partitions, including writers running older code.
-- Flyway must execute this migration transactionally. LOCK without ONLY recursively locks
-- all attached descendants as well as the parent before the scan/backfill/trigger installation.
LOCK TABLE transactions IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM transactions GROUP BY idempotency_key HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Global transaction idempotency migration requires reconciliation';
    END IF;
END;
$$;

CREATE TABLE transaction_idempotency_keys (
    idempotency_key VARCHAR(100) NOT NULL,
    transaction_id UUID NOT NULL,
    booking_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_transaction_idempotency_keys PRIMARY KEY (idempotency_key)
);

INSERT INTO transaction_idempotency_keys (idempotency_key, transaction_id, booking_date)
SELECT idempotency_key, id, booking_date FROM transactions;

CREATE FUNCTION claim_transaction_idempotency_key() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO transaction_idempotency_keys (idempotency_key, transaction_id, booking_date)
    VALUES (NEW.idempotency_key, NEW.id, NEW.booking_date);
    RETURN NEW;
END;
$$;

-- PostgreSQL clones row triggers onto existing and future attached partitions.
CREATE TRIGGER claim_transaction_idempotency_key
BEFORE INSERT ON transactions
FOR EACH ROW EXECUTE FUNCTION claim_transaction_idempotency_key();

CREATE FUNCTION guard_transaction_idempotency_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.idempotency_key, NEW.id, NEW.booking_date)
        IS DISTINCT FROM (OLD.idempotency_key, OLD.id, OLD.booking_date) THEN
        RAISE EXCEPTION 'Transaction idempotency identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_transaction_idempotency_identity
BEFORE UPDATE ON transactions
FOR EACH ROW EXECUTE FUNCTION guard_transaction_idempotency_identity();
-- Deliberately no DELETE trigger: retained claims prevent reuse after archival/deletion.
-- Safe application rollback retains this registry and both triggers. Removing protection is
-- a separately approved maintenance operation with every writer AND retry source quiesced;
-- never release keys or remove financial history as a rollback. See service docs, V22 rollout.
