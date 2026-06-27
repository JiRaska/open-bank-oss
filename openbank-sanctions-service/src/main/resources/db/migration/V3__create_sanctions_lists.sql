-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE IF NOT EXISTS sanctions_lists (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_type       VARCHAR(30) NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    source_url      TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    last_updated_at TIMESTAMPTZ,
    last_entry_count INT,
    cron_hour       INT NOT NULL DEFAULT 6,
    cron_minute     INT NOT NULL DEFAULT 0,
    cron_days       VARCHAR(20) NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO sanctions_lists (list_type, display_name, source_url, cron_hour, cron_minute, cron_days) VALUES
  ('OFAC_SDN',       'OFAC SDN List',            'https://www.treasury.gov/ofac/downloads/sdn.xml',                    6, 0, 'MON,TUE,WED,THU,FRI'),
  ('EU_CONSOLIDATED','EU Consolidated List',      'https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content',  6,15,'MON,TUE,WED,THU,FRI'),
  ('UN_CONSOLIDATED','UN Consolidated List',      'https://scsanctions.un.org/resources/xml/en/consolidated.xml',      6,30,'MON,WED,FRI'),
  ('HM_TREASURY',    'HM Treasury UK List',       'https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml', 7, 0,'MON,TUE,WED,THU,FRI'),
  ('FATF_HIGH_RISK', 'FATF High-Risk Countries',  'https://www.fatf-gafi.org/content/dam/fatf-gafi/publications/high-risk-jurisdictions.xml', 8, 0,'MON'),
  ('PEP_GLOBAL',     'PEP Global List',           'https://data.opensanctions.org/datasets/latest/peps/targets.simple.csv', 6,45,'MON,TUE,WED,THU,FRI'),
  ('CNB_DOMESTIC',   'ČNB Domestic List',         'https://www.cnb.cz/cs/mezinarodni-sankce/seznam-sankcionovanych-subjektu/', 7,30,'MON,TUE,WED,THU,FRI')
ON CONFLICT (list_type) DO NOTHING;
