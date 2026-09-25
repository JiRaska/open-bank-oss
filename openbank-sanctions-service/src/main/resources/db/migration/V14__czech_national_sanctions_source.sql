-- V14: Import the Czech national sanctions list for real (#10757).
-- CNB_DOMESTIC used to be three Flyway V6 demo rows behind a SKIPPED import outcome. The list is
-- kept by the Ministry of Foreign Affairs (MZV) under Act No. 1/2023 Coll., not by ČNB; MZV
-- publishes it as open data under a dated filename per edition, so the stable OpenSanctions
-- mirror is used. The first import deactivates the demo rows via the reconciliation sweep.
-- The list_type key stays CNB_DOMESTIC: it is part of the public API and event schema.
--
-- Rollback: restore the previous values —
--   UPDATE sanctions_lists SET display_name = 'ČNB Domestic List',
--     source_url = 'https://www.cnb.cz/cs/mezinarodni-sankce/seznam-sankcionovanych-subjektu/'
--   WHERE list_type = 'CNB_DOMESTIC';
-- and re-activate the seed rows (external_id cnb-001..cnb-003) if they are wanted back.

UPDATE sanctions_lists
SET display_name = 'Czech National Sanctions List (MZV)',
    source_url   = 'https://data.opensanctions.org/datasets/latest/cz_national_sanctions/targets.simple.csv',
    updated_at   = NOW()
WHERE list_type = 'CNB_DOMESTIC';
