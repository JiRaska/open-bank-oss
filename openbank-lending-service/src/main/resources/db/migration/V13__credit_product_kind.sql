-- SPDX-License-Identifier: Apache-2.0
-- ADR-0269 rule 3: one CreditApplication journey for three product shapes.
--
-- product_kind is the shape (UNSECURED / SECURED / REVOLVING); the existing product_type stays
-- what it always was — the ADR-0212 compliance-pack product identifier — and the two are not
-- interchangeable. A pack decides which rules judge the application; the kind decides which steps
-- the customer walks.
--
-- Backfill is UNSECURED because that is what every application in the book actually is today: the
-- only origination route that exists is the cash-loan intake. Defaulting to a kind nobody has
-- applied for would invent a product history.
--
-- Rollback: ALTER TABLE loan_application DROP COLUMN product_kind;  (the column is additive and
-- nothing reads it until the projection endpoint ships in the same release)

ALTER TABLE loan_application
    ADD COLUMN IF NOT EXISTS product_kind varchar(16) NOT NULL DEFAULT 'UNSECURED';

-- Rows written before this migration are unsecured cash loans by construction; make that explicit
-- rather than relying on the DEFAULT, so a later DEFAULT change cannot retroactively relabel them.
UPDATE loan_application SET product_kind = 'UNSECURED' WHERE product_kind IS NULL;

COMMENT ON COLUMN loan_application.product_kind IS
    'ADR-0269 credit product shape: UNSECURED | SECURED | REVOLVING. Not the compliance-pack product_type.';
