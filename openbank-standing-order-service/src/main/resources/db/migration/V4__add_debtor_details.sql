-- SPDX-License-Identifier: Apache-2.0
-- #889: standing orders never executed because the SEPA rail's createPayment needs the debtor's
-- IBAN + name, which the order did not carry and account-service's by-id response does not expose.
-- Capture them at creation instead. Nullable for backward compatibility with pre-existing orders:
-- such orders remain creatable but the SEPA rail records an explicit failure ("missing debtor
-- details") rather than dispatching a malformed payment.
--
-- Rollback: ALTER TABLE standing_orders DROP COLUMN debtor_name; ALTER TABLE standing_orders DROP COLUMN debtor_iban;
ALTER TABLE standing_orders ADD COLUMN IF NOT EXISTS debtor_iban VARCHAR(34);
ALTER TABLE standing_orders ADD COLUMN IF NOT EXISTS debtor_name VARCHAR(140);
