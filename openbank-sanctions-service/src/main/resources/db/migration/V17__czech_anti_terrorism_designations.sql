-- V17: Screen the separate Czech anti-terrorism legal regime (#10760).
-- Source of record: official e-Sbírka SPARQL open data for Regulation No. 210/2008 Coll.,
-- version 2009-04-01. Do not substitute OpenSanctions cz_terrorists: that commercially licensed
-- mirror is not the approved legal source. CNB_DOMESTIC remains the distinct Act 1/2023 Coll. list.
-- The scheduler supports weekdays rather than day-of-month, so refresh weekly off peak.
--
-- Rollback: DELETE FROM sanctions_entries WHERE list_type = 'CZ_TERRORISTS';
--           DELETE FROM sanctions_lists WHERE list_type = 'CZ_TERRORISTS';

INSERT INTO sanctions_lists (
    list_type, display_name, source_url, enabled, cron_hour, cron_minute, cron_days
) VALUES (
    'CZ_TERRORISTS',
    'Czech Anti-Terrorism Designations (210/2008 Coll.)',
    'https://opendata.eselpoint.gov.cz/sparql?output=text%2Fcsv&query=PREFIX%20p%3A%20%3Chttps%3A%2F%2Fslovn%C3%ADk.gov.cz%2Fdatov%C3%BD%2Fsb%C3%ADrka%2Fpojem%2F%3E%0ASELECT%20%3Fdesignation%20%3Ffragment%20%3Ftext%20WHERE%20%7B%0A%20%20VALUES%20%3Fsection%20%7B%0A%20%20%20%20%3Chttps%3A%2F%2Fopendata.eselpoint.gov.cz%2Fesel-esb%2Feli%2Fcz%2Fsb%2F2008%2F210%2F2009-04-01%2Fdokument%2Fprilohy%2Fpriloha_0%2Fcast_1%3E%0A%20%20%20%20%3Chttps%3A%2F%2Fopendata.eselpoint.gov.cz%2Fesel-esb%2Feli%2Fcz%2Fsb%2F2008%2F210%2F2009-04-01%2Fdokument%2Fprilohy%2Fpriloha_0%2Fcast_2%3E%0A%20%20%7D%0A%20%20%3Fdesignation%20p%3Am%C3%A1-p%C5%99edka%20%3Fsection%20%3B%20p%3Aobsahuje-fragment%20%3Ffragment%20.%0A%20%20%3Ffragment%20p%3Atext-fragmentu%20%3Ftext%20.%0A%7D%20ORDER%20BY%20%3Fdesignation',
    TRUE,
    4,
    17,
    'MON'
)
ON CONFLICT (list_type) DO NOTHING;
