-- Phone directory (pay-to-phone). Two columns, both opt-in by construction:
--
--   discoverable — whether this party may be FOUND by someone who knows their phone number.
--     Defaults to FALSE for every existing and future row. Findability has to be a decision the
--     customer makes: a bank that answers "yes, that number banks with us" for anyone who guesses
--     a number is leaking the existence of a customer relationship.
--
--   phone_hash — SHA-256 of the E.164 phone number, so the lookup never carries or compares a
--     plaintext number. This keeps plaintext out of request bodies, logs and indexes; it is NOT a
--     privacy guarantee against this service, which holds both the numbers and the hashes and
--     could brute-force the (small) phone-number space regardless. Said plainly here so nobody
--     reads the column name as stronger than it is.
--
-- The partial index only covers discoverable rows: a non-discoverable party must never be
-- matchable, and keeping them out of the index makes that cheap as well as correct.
ALTER TABLE parties ADD COLUMN discoverable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE parties ADD COLUMN phone_hash CHAR(64);

-- Backfill for rows that already have a phone. normalise: drop everything but digits and a
-- leading +, then assume the Czech country code for a bare 9-digit national number. Rows whose
-- phone does not normalise to something plausible are left NULL — an unmatched row is a missed
-- convenience, a WRONGLY matched one is a payment to a stranger.
UPDATE parties
SET phone_hash = encode(
        sha256(
            convert_to(
                CASE
                    WHEN regexp_replace(phone, '[^0-9]', '', 'g') ~ '^[1-9][0-9]{8}$'
                        THEN '+420' || regexp_replace(phone, '[^0-9]', '', 'g')
                    WHEN phone LIKE '+%' AND length(regexp_replace(phone, '[^0-9]', '', 'g')) BETWEEN 8 AND 15
                        THEN '+' || regexp_replace(phone, '[^0-9]', '', 'g')
                    ELSE NULL
                END,
                'UTF8'
            )
        ),
        'hex'
    )
WHERE phone IS NOT NULL;

CREATE INDEX idx_parties_phone_hash_discoverable
    ON parties (phone_hash)
    WHERE discoverable AND phone_hash IS NOT NULL;
