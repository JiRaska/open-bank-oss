-- V7: Switch EU/UN/HM Treasury source URLs to OpenSanctions CSV format.
-- Official XML endpoints (webgate.ec.europa.eu, scsanctions.un.org, ofsistorage.blob.core.windows.net)
-- returned HTML redirects or used XML structures not matching our parser.
-- OpenSanctions normalises all lists into the same targets.simple.csv format,
-- which our importOpenSanctionsCsv importer already handles correctly.

UPDATE sanctions_lists
SET source_url = 'https://data.opensanctions.org/datasets/latest/eu_fsf/targets.simple.csv',
    updated_at  = NOW()
WHERE list_type = 'EU_CONSOLIDATED';

UPDATE sanctions_lists
SET source_url = 'https://data.opensanctions.org/datasets/latest/un_sc_sanctions/targets.simple.csv',
    updated_at  = NOW()
WHERE list_type = 'UN_CONSOLIDATED';

UPDATE sanctions_lists
SET source_url = 'https://data.opensanctions.org/datasets/latest/gb_hmt_sanctions/targets.simple.csv',
    updated_at  = NOW()
WHERE list_type = 'HM_TREASURY';

-- Reset FATF entry count to 0 (it is country-risk, not entity-based; no entries in sanctions_entries).
-- The old fake count (133) was left over from the calculateEntryCount() stub.
UPDATE sanctions_lists
SET last_entry_count = 0,
    updated_at       = NOW()
WHERE list_type = 'FATF_HIGH_RISK';
