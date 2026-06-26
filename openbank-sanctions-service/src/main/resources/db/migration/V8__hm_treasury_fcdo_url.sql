-- V8: Fix HM Treasury source URL to use gb_fcdo_sanctions (FCDO + OFSI combined).
-- gb_hmt_sanctions on OpenSanctions returned an empty CSV (header only, 175 bytes).
-- gb_fcdo_sanctions contains the actual UK government sanctions list (~6.7 MB).

UPDATE sanctions_lists
SET source_url = 'https://data.opensanctions.org/datasets/latest/gb_fcdo_sanctions/targets.simple.csv',
    updated_at  = NOW()
WHERE list_type = 'HM_TREASURY';
