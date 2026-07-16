-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0171 — Verification of Payee (Reg. (EU) 2024/886 Art. 5c): evidence that the check ran.
-- Rollback: DROP TABLE vop_verification;  (no dependents; evidence history is lost on rollback)
--
-- GDPR Art. 5(1)(c): the payee name and IBAN are stored ONLY as SHA-256 hashes. Proving the
-- control ran does not require retaining every name typed into a payment form. The hashes still
-- answer the one question a fraud claim asks — "did we check this name against this IBAN, and
-- what did we say?" — because the claimant supplies the inputs.
--
-- Retention is 13 months (the fraud-claim window), NOT the 7-year accounting default: these are
-- not accounting records. Enforcement is a follow-up scheduler, per the ADR-0118 pattern.

CREATE TABLE vop_verification (
    id                 UUID         NOT NULL,
    iban_hash          CHAR(64)     NOT NULL,
    supplied_name_hash CHAR(64)     NOT NULL,
    outcome            VARCHAR(16)  NOT NULL,
    no_data_reason     VARCHAR(32),
    requested_by       VARCHAR(255) NOT NULL,
    verified_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_vop_verification PRIMARY KEY (id),
    -- The domain invariant, enforced at the storage layer too: a reason iff the outcome is
    -- NO_DATA. VopVerification's init block asserts the same thing.
    CONSTRAINT ck_vop_no_data_reason CHECK (
        (outcome = 'NO_DATA' AND no_data_reason IS NOT NULL)
        OR (outcome <> 'NO_DATA' AND no_data_reason IS NULL)
    )
);

-- Fraud-claim lookup: "what did we answer for this IBAN + name?", newest first.
CREATE INDEX ix_vop_verification_lookup ON vop_verification (iban_hash, supplied_name_hash, verified_at DESC);

-- Retention sweep and per-requester rate/abuse review both scan by time.
CREATE INDEX ix_vop_verification_verified_at ON vop_verification (verified_at);
