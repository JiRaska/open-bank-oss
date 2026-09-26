-- #10281 / ADR-0312 addendum: standing orders and SDD mandates created for a legal entity are held
-- for co-signature and released exactly like a payment.
-- Rollback: only while no STANDING_ORDER/SDD_MANDATE row exists — delete them, then restore the
-- two constraints as V25 wrote them.
ALTER TABLE approval_requests DROP CONSTRAINT approval_requests_kind_check;
ALTER TABLE approval_requests ADD CONSTRAINT approval_requests_kind_check
    CHECK (kind IN ('PAYMENT', 'PAYEE_ADD', 'PAYEE_REMOVE', 'POLICY_CHANGE', 'STANDING_ORDER', 'SDD_MANDATE'));

ALTER TABLE approval_requests DROP CONSTRAINT chk_approval_release_only_payment;
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_release_only_payment CHECK (
    kind IN ('PAYMENT', 'STANDING_ORDER', 'SDD_MANDATE') OR status NOT IN ('RELEASED', 'RELEASE_FAILED')
);
