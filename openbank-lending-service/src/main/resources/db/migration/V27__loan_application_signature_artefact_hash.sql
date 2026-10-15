-- SPDX-License-Identifier: Apache-2.0
-- ADR-0211 D1/D6 + ADR-0214: persist the signature artefact hash recorded by the explicit
-- sign command (POST /api/v1/lending/applications/{id}/sign, AWAITING_SIGNATURE -> SIGNED).
-- Nullable: every row pre-dating the sign endpoint has no signature artefact, and the value
-- is set exactly once by the sign claim (compareAndSetSignature) and never updated after.
--
-- Rollback:
--   ALTER TABLE loan_application DROP COLUMN signature_artefact_hash;
-- Re-running the sign command after a rollback would fail the machine's state check
-- (SIGNED rows are terminal for the signature edge), so no row can lose its only recorded
-- hash silently; the hash also remains in the ADR-0214 evidence event of the transition.

ALTER TABLE loan_application
    ADD COLUMN signature_artefact_hash varchar(64);
