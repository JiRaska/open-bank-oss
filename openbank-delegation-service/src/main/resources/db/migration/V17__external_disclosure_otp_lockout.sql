-- External disclosure is a public magic-link boundary. A wrong OTP must consume a durable,
-- bounded attempt rather than throw and roll back, otherwise six-digit OTPs are brute-forceable.
-- Rollback: ALTER TABLE delegation_external_disclosures DROP CONSTRAINT chk_external_disclosure_failed_otp_attempts, DROP COLUMN locked_at, DROP COLUMN failed_otp_attempts;
ALTER TABLE delegation_external_disclosures
    ADD COLUMN failed_otp_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_external_disclosure_failed_otp_attempts CHECK (failed_otp_attempts >= 0);
