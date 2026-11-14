-- SPDX-License-Identifier: Apache-2.0
-- FATF jurisdiction statements of 30 October 2026 (plenary 28-30 October 2026), issue #15723.
--
-- SOURCES. The original statements are:
--   https://www.fatf-gafi.org/en/publications/High-risk-and-other-monitored-jurisdictions/call-for-action-october-2026.html
--   https://www.fatf-gafi.org/en/publications/High-risk-and-other-monitored-jurisdictions/increased-monitoring-october-2026.html
-- fatf-gafi.org refuses automated retrieval (a bot check), and rows are never written from memory,
-- so the rows below were taken from an official government republication: the Japan Ministry of
-- Finance provisional translations of both statements, linked from
--   https://www.mof.go.jp/policy/international_policy/convention/fatf/20261106180337.html
--   Annex 1, "High-Risk Jurisdictions subject to a Call for Action, October 2026":
--     https://www.mof.go.jp/policy/international_policy/convention/fatf/20261109_1.pdf
--     SHA-256 9d0f1c1743e6f01384128d6ba7163e780a572061b13e447a75642b65d21404cf
--   Annex 2, "Jurisdictions under Increased Monitoring, 30 October 2026":
--     https://www.mof.go.jp/policy/international_policy/amlcftcpf/20261109_2.pdf
--     SHA-256 0d1c527b8ded0a2b576a92267007c359032ce2915bfc75ab403b7e7cb66a8341
-- and cross-checked against the US FinCEN release of 30 October 2026:
--   https://www.fincen.gov/news/news-releases/financial-action-task-force-identifies-jurisdictions-anti-money-laundering-6
-- (Iran and DPRK countermeasures; Burma/Myanmar enhanced due diligence, not countermeasures; the
-- same four removals). Compared row by row on 2026-11-14.
--
-- REMOVED by this plenary from increased monitoring, and therefore NOT loaded: Bulgaria (BG),
-- Côte d'Ivoire (CI), Democratic Republic of the Congo (CD), Monaco (MC). CI, CD and MC remain on
-- the EU list (V22) until the Commission amends Delegated Regulation (EU) 2016/1675; that list is
-- binding and is left exactly as it is.
--
-- MEASURES (ADR-0032, amendment of 2026-11-14).
--   FATF_CALL_FOR_ACTION -> HIGH_RISK for all three. DPRK and Iran are subject to COUNTERMEASURES,
--     Myanmar to enhanced due diligence only; source_ref records which. EMBARGO here means a
--     screen HIT, i.e. a blocked payment, and neither the FATF statement, an EU/UN row in this
--     table nor an ADR makes a FATF countermeasure a blanket refusal: countermeasures are applied
--     by the obliged entity's own procedures on top of enhanced due diligence. Every one of the
--     three is already HIGH_RISK through the EU list, so no screen outcome changes; the rows add
--     FATF provenance to the evidence.
--   FATF_INCREASED_MONITORING -> RISK_FACTOR, a new measure that NO screen turns into a match.
--     The grey list is a risk factor to be weighed (the statement itself: FATF does not call for
--     enhanced due diligence on these jurisdictions and expects a risk-based approach, not
--     de-risking), so it must not send every payment to, say, Kuwait or Iraq to review. The rows
--     are loaded so the data is correct and its age is observed; customer risk scoring is the
--     consumer it is meant for (not wired yet).
--     These rows use list_type = COUNTRY_RISK_FACTOR, a slot that is not a screening list: every
--     read of this table (SanctionsJurisdictionRepositoryImpl, unchanged since V21) selects only
--     list_type = ANY(<SanctionsListType names of the screen>), so no screen of this release or of
--     the previous one ever selects them. That is what makes the previous release, whose
--     JurisdictionMeasure has no RISK_FACTOR, safe against this data (JurisdictionRollbackSafetyIT).
--
-- Rollback property: the previous release runs unchanged against the migrated schema and data.
-- It never selects a COUNTRY_RISK_FACTOR row, and the three call-for-action rows are HIGH_RISK,
-- which it parses; its screen outcomes are identical (KP, IR, MM were already HIGH_RISK via the
-- EU list). No manual step is needed. To remove the data as well, a later forward migration:
-- DELETE FROM sanctions_jurisdictions WHERE source_list IN ('FATF_CALL_FOR_ACTION',
-- 'FATF_INCREASED_MONITORING') AND version = 1.

ALTER TABLE sanctions_jurisdictions DROP CONSTRAINT sanctions_jurisdictions_measure_check;
ALTER TABLE sanctions_jurisdictions ADD CONSTRAINT sanctions_jurisdictions_measure_check
    CHECK (measure IN ('EMBARGO', 'HIGH_RISK', 'RISK_FACTOR'));

INSERT INTO sanctions_jurisdictions
    (list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on)
SELECT 'FATF_HIGH_RISK',
       'FATF_CALL_FOR_ACTION',
       listed.country_code,
       'HIGH_RISK',
       1,
       'FATF, High-Risk Jurisdictions subject to a Call for Action, October 2026 (published 2026-10-30): '
           || listed.fatf_measure
           || '; read from Japan MOF provisional translation '
           || 'https://www.mof.go.jp/policy/international_policy/convention/fatf/20261109_1.pdf '
           || 'SHA-256 9d0f1c1743e6f01384128d6ba7163e780a572061b13e447a75642b65d21404cf',
       DATE '2026-10-30',
       DATE '2026-11-14'
FROM (VALUES
    ('KP', 'countermeasures')  /* Democratic People's Republic of Korea */,
    ('IR', 'countermeasures')  /* Iran */,
    ('MM', 'enhanced due diligence, not countermeasures')  /* Myanmar */
) AS listed (country_code, fatf_measure);

INSERT INTO sanctions_jurisdictions
    (list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on)
SELECT 'COUNTRY_RISK_FACTOR',
       'FATF_INCREASED_MONITORING',
       listed.country_code,
       'RISK_FACTOR',
       1,
       'FATF, Jurisdictions under Increased Monitoring, 30 October 2026 (published 2026-10-30): '
           || 'risk factor, no enhanced due diligence called for; read from Japan MOF provisional translation '
           || 'https://www.mof.go.jp/policy/international_policy/amlcftcpf/20261109_2.pdf '
           || 'SHA-256 0d1c527b8ded0a2b576a92267007c359032ce2915bfc75ab403b7e7cb66a8341',
       DATE '2026-10-30',
       DATE '2026-11-14'
FROM (VALUES
    ('AO')  /* Angola */,
    ('BO')  /* Bolivia */,
    ('BA')  /* Bosnia and Herzegovina */,
    ('CM')  /* Cameroon */,
    ('HT')  /* Haiti */,
    ('IQ')  /* Iraq */,
    ('KE')  /* Kenya */,
    ('KW')  /* Kuwait */,
    ('LA')  /* Lao PDR */,
    ('LB')  /* Lebanon */,
    ('NP')  /* Nepal */,
    ('PG')  /* Papua New Guinea */,
    ('SS')  /* South Sudan */,
    ('SY')  /* Syria */,
    ('VE')  /* Venezuela */,
    ('VN')  /* Vietnam */,
    ('VG')  /* Virgin Islands (British) */,
    ('YE')  /* Yemen */
) AS listed (country_code);
