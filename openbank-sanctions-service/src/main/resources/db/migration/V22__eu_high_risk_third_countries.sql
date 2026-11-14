-- SPDX-License-Identifier: Apache-2.0
-- First jurisdiction reference data (issue #15723): the EU list of high-risk third countries.
--
-- SOURCE. Commission Delegated Regulation (EU) 2016/1675 supplementing Directive (EU) 2015/849,
-- Annex "High-risk third countries", consolidated version 02016R1675-20260129 (EUR-Lex), as last
-- amended by Delegated Regulations (EU) 2026/46 and (EU) 2026/83 (OJ L, 9.1.2026). Retrieved from
-- EUR-Lex and compared row by row on 2026-11-11; EUR-Lex listed no later consolidated version and
-- no later amending act on that day. 26 countries: Annex point I (23), II (1), III (1), IV (1).
-- The consolidated text is a documentation tool; the authentic acts are the ones in the OJ.
--
-- MEASURE = HIGH_RISK for every row, including Annex points II and III. The regulation's legal
-- effect is the one Directive (EU) 2015/849 attaches to a high-risk third country: enhanced due
-- diligence (Article 18a), i.e. a review, not a prohibition. Points II and III also say those two
-- countries are "identified by FATF Public Statement", but the measure a FATF call for action
-- asks for, and whether this bank treats it as an embargo, is a legal judgement that has to be
-- made on the FATF statement itself. That statement could not be retrieved when this migration
-- was written, so no row is marked EMBARGO and no row cites FATF.
--
-- NOT LOADED, deliberately:
--   * the FATF "High-Risk Jurisdictions subject to a Call for Action" and "Jurisdictions under
--     Increased Monitoring" lists. The publisher's site refused automated retrieval on 2026-11-11
--     and rows are never written from memory. Both stay empty and are reported as unpopulated
--     (openbank_sanctions_jurisdiction_rows = 0) until a migration loads them from the published
--     statement;
--   * any comprehensive country embargo. EU restrictive measures against a country are a set of
--     entity, sector and activity prohibitions, not a list of embargoed countries, and no source
--     cited here states one.
--
-- list_type = FATF_HIGH_RISK is the screening-list slot for country risk (the list whose entity
-- import is skipped as "not entity based"); it is not a claim that FATF published these rows.
-- Provenance is source_list, which the screen also returns in a match's `programs`.
--
-- UPDATING. A new consolidated version is a new migration: insert its rows with version + 1 and
-- the new published_on, and set active = false on the rows it supersedes. A re-verification that
-- finds the source unchanged is also a migration:
--   UPDATE sanctions_jurisdictions SET verified_on = DATE '<today>'
--    WHERE source_list = 'EU_HIGH_RISK_THIRD_COUNTRIES' AND active;
-- The alert on openbank_sanctions_jurisdiction_verification_age_seconds is what asks for it.
--
-- Rollback: DELETE FROM sanctions_jurisdictions WHERE source_list = 'EU_HIGH_RISK_THIRD_COUNTRIES'
-- AND version = 1. Reference data only; no other table refers to these rows.

INSERT INTO sanctions_jurisdictions
    (list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on)
SELECT 'FATF_HIGH_RISK',
       'EU_HIGH_RISK_THIRD_COUNTRIES',
       listed.country_code,
       'HIGH_RISK',
       1,
       'Commission Delegated Regulation (EU) 2016/1675, ' || listed.annex_point
           || ', consolidated version 02016R1675-20260129 (published 2026-01-29)',
       DATE '2026-01-29',
       DATE '2026-11-11'
FROM (VALUES
    ('AF', 'Annex point I')  /* Afghanistan */,
    ('DZ', 'Annex point I')  /* Algeria */,
    ('AO', 'Annex point I')  /* Angola */,
    ('BO', 'Annex point I')  /* Bolivia */,
    ('VG', 'Annex point I')  /* British Virgin Islands */,
    ('CM', 'Annex point I')  /* Cameroon */,
    ('CI', 'Annex point I')  /* Côte d’Ivoire */,
    ('CD', 'Annex point I')  /* Democratic Republic of the Congo */,
    ('HT', 'Annex point I')  /* Haiti */,
    ('KE', 'Annex point I')  /* Kenya */,
    ('LA', 'Annex point I')  /* Laos */,
    ('LB', 'Annex point I')  /* Lebanon */,
    ('MC', 'Annex point I')  /* Monaco */,
    ('MM', 'Annex point I')  /* Myanmar */,
    ('NA', 'Annex point I')  /* Namibia */,
    ('NP', 'Annex point I')  /* Nepal */,
    ('SS', 'Annex point I')  /* South Sudan */,
    ('SY', 'Annex point I')  /* Syria */,
    ('TT', 'Annex point I')  /* Trinidad and Tobago */,
    ('VU', 'Annex point I')  /* Vanuatu */,
    ('VE', 'Annex point I')  /* Venezuela */,
    ('VN', 'Annex point I')  /* Vietnam */,
    ('YE', 'Annex point I')  /* Yemen */,
    ('IR', 'Annex point II')  /* Iran */,
    ('KP', 'Annex point III')  /* Democratic People’s Republic of Korea (DPRK) */,
    ('RU', 'Annex point IV')  /* Russian Federation */
) AS listed (country_code, annex_point);
