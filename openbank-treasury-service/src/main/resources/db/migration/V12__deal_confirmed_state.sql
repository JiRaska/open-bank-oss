-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D2: the CONFIRMED state (counterparty confirmation received) between BOOKED and SETTLED.
-- Only the state constraint changes. NO data migration: a deal BOOKED before this version stays
-- BOOKED — marking it CONFIRMED would record a confirmation nobody received. Whether such a deal
-- may settle straight from BOOKED is `openbank.treasury.confirmation.required` (default true);
-- in the sandbox the simulated market confirms every BOOKED deal on its next pass.
--
-- V12 because V10 is claimed by the open nostro PR (#11113) and V11 is on main.
--
-- Rollback (only while no CONFIRMED row exists — check first; move any back to BOOKED by hand
-- only if the confirmation is to be discarded):
--   ALTER TABLE deals DROP CONSTRAINT deals_state_check;
--   ALTER TABLE deals ADD CONSTRAINT deals_state_check
--       CHECK (state IN ('DRAFT','PENDING_APPROVAL','BOOKED','SETTLED','MATURED','CANCELLED','REVERSED'));

ALTER TABLE deals DROP CONSTRAINT deals_state_check;
ALTER TABLE deals ADD CONSTRAINT deals_state_check
    CHECK (state IN ('DRAFT','PENDING_APPROVAL','BOOKED','CONFIRMED','SETTLED','MATURED','CANCELLED','REVERSED'));
