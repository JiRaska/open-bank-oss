-- SPDX-License-Identifier: MPL-2.0
-- Seed representative entries for sandbox/dev.
-- Production entries are populated via SanctionsImportService (scheduled download).
-- search_text = unaccented lowercase of all names — mirrors what the import service computes.

INSERT INTO sanctions_entries (list_type, external_id, entity_type, primary_name, aliases_json, date_of_birth, nationalities, programs, search_text) VALUES

-- ============================================================
-- OFAC SDN
-- ============================================================
('OFAC_SDN','ofac-17766','INDIVIDUAL','Vladimir Putin',
 '["Vladimir Vladimirovich Putin","V.V. Putin","Putin"]',
 '1952-10-07','["ru"]','["RUSSIA-EO14024","RUSSIA-EO14024A"]',
 'vladimir putin | vladimir vladimirovich putin | v.v. putin | putin'),

('OFAC_SDN','ofac-34073','INDIVIDUAL','Alexander Lukashenko',
 '["Alyaksandr Ryhoravich Lukashenka","Lukashenko","Lukashenka"]',
 '1954-08-30','["by"]','["BELARUS-SANCTIONS"]',
 'alexander lukashenko | alyaksandr ryhoravich lukashenka | lukashenko | lukashenka'),

('OFAC_SDN','ofac-14579','INDIVIDUAL','Nicolas Maduro Moros',
 '["Nicolas Maduro","Maduro Moros","Maduro"]',
 '1962-11-23','["ve"]','["VENEZUELA-EO13808"]',
 'nicolas maduro moros | nicolas maduro | maduro moros | maduro'),

('OFAC_SDN','ofac-6769','INDIVIDUAL','Ali Khamenei',
 '["Sayyed Ali Hosseini Khamenei","Ali Hoseini-Khamenei","Khamenei"]',
 '1939-07-17','["ir"]','["IRAN"]',
 'ali khamenei | sayyed ali hosseini khamenei | ali hoseini-khamenei | khamenei'),

('OFAC_SDN','ofac-20704','INDIVIDUAL','Kim Jong Un',
 '["Kim Jong-un","Kim Cho'||chr(39)||'ng-un","KJU"]',
 '1984-01-08','["kp"]','["DPRK"]',
 'kim jong un | kim jong-un | kju'),

('OFAC_SDN','ofac-32887','INDIVIDUAL','Sergei Lavrov',
 '["Sergey Viktorovich Lavrov","Lavrov"]',
 '1950-03-21','["ru"]','["RUSSIA-EO14024"]',
 'sergei lavrov | sergey viktorovich lavrov | lavrov'),

('OFAC_SDN','ofac-9764','INDIVIDUAL','Viktor Vekselberg',
 '["Viktor Felixovich Vekselberg","Vekselberg"]',
 '1957-04-14','["ru"]','["RUSSIA-EO14024"]',
 'viktor vekselberg | viktor felixovich vekselberg | vekselberg'),

('OFAC_SDN','ofac-4038','ORGANIZATION','Hamas',
 '["Harakat Al-Muqawama Al-Islamiyya","Islamic Resistance Movement"]',
 NULL,'[]','["SDGT"]',
 'hamas | harakat al-muqawama al-islamiyya | islamic resistance movement'),

('OFAC_SDN','ofac-5853','ORGANIZATION','Hezbollah',
 '["Hizballah","Hizbullah","Party of God"]',
 NULL,'["lb"]','["SDGT"]',
 'hezbollah | hizballah | hizbullah | party of god'),

('OFAC_SDN','ofac-6365','ORGANIZATION','Al-Qaeda',
 '["Al Qaida","Al-Qa''ida","Qaeda","Al Qaida in Iraq"]',
 NULL,'[]','["SDGT"]',
 'al-qaeda | al qaida | al-qa ida | qaeda'),

('OFAC_SDN','ofac-34049','ORGANIZATION','Wagner Group',
 '["PMC Wagner","Grupa Vagnera","LLC Concord Management"]',
 NULL,'["ru"]','["RUSSIA-EO14024"]',
 'wagner group | pmc wagner | grupa vagnera | llc concord management'),

-- ============================================================
-- EU CONSOLIDATED
-- ============================================================
('EU_CONSOLIDATED','eu-putin-1','INDIVIDUAL','Vladimir Putin',
 '["Vladimir Vladimirovich Putin","Putin"]',
 '1952-10-07','["ru"]','["RUSSIA"]',
 'vladimir putin | vladimir vladimirovich putin | putin'),

('EU_CONSOLIDATED','eu-lukashenko-1','INDIVIDUAL','Alexander Lukashenko',
 '["Alyaksandr Lukashenka","Lukashenko"]',
 '1954-08-30','["by"]','["BELARUS"]',
 'alexander lukashenko | alyaksandr lukashenka | lukashenko'),

('EU_CONSOLIDATED','eu-lavrov-1','INDIVIDUAL','Sergei Lavrov',
 '["Sergey Lavrov","Lavrov"]',
 '1950-03-21','["ru"]','["RUSSIA"]',
 'sergei lavrov | sergey lavrov | lavrov'),

('EU_CONSOLIDATED','eu-medvedev-1','INDIVIDUAL','Dmitry Medvedev',
 '["Dmitri Anatolyevich Medvedev","Medvedev"]',
 '1965-09-14','["ru"]','["RUSSIA"]',
 'dmitry medvedev | dmitri anatolyevich medvedev | medvedev'),

('EU_CONSOLIDATED','eu-yanukovych-1','INDIVIDUAL','Viktor Yanukovych',
 '["Viktor Fedorovych Yanukovych","Yanukovych"]',
 '1950-07-09','["ua"]','["UKRAINE-MISAPPROPRIATION"]',
 'viktor yanukovych | viktor fedorovych yanukovych | yanukovych'),

('EU_CONSOLIDATED','eu-patrushev-1','INDIVIDUAL','Nikolai Patrushev',
 '["Nikolay Platonovich Patrushev","Patrushev"]',
 '1951-07-11','["ru"]','["RUSSIA"]',
 'nikolai patrushev | nikolay platonovich patrushev | patrushev'),

('EU_CONSOLIDATED','eu-khamenei-1','INDIVIDUAL','Ali Khamenei',
 '["Sayyed Ali Khamenei","Khamenei"]',
 '1939-07-17','["ir"]','["IRAN"]',
 'ali khamenei | sayyed ali khamenei | khamenei'),

('EU_CONSOLIDATED','eu-hamas-1','ORGANIZATION','Hamas',
 '["Harakat Al-Muqawama Al-Islamiyya"]',
 NULL,'[]','["TERRORISM"]',
 'hamas | harakat al-muqawama al-islamiyya'),

('EU_CONSOLIDATED','eu-al-assad-1','INDIVIDUAL','Bashar Al-Assad',
 '["Bashar Al-Asad","Assad"]',
 '1965-09-11','["sy"]','["SYRIA"]',
 'bashar al-assad | bashar al-asad | al-assad | al-asad | assad'),

-- ============================================================
-- UN CONSOLIDATED
-- ============================================================
('UN_CONSOLIDATED','un-kim-1','INDIVIDUAL','Kim Jong Un',
 '["Kim Jong-un","Kim Cho'||chr(39)||'ng-un"]',
 '1984-01-08','["kp"]','["DPRK"]',
 'kim jong un | kim jong-un'),

('UN_CONSOLIDATED','un-choe-1','INDIVIDUAL','Choe Ryong Hae',
 '["Ch''oe Ryong-hae"]',
 '1950-01-01','["kp"]','["DPRK"]',
 'choe ryong hae | ch oe ryong-hae'),

('UN_CONSOLIDATED','un-taliban-1','ORGANIZATION','Taliban',
 '["Taleban","Islamic Emirate of Afghanistan"]',
 NULL,'["af"]','["TALIBAN"]',
 'taliban | taleban | islamic emirate of afghanistan'),

('UN_CONSOLIDATED','un-aq-1','ORGANIZATION','Al-Qaeda',
 '["Al Qaida","Al-Qa''ida"]',
 NULL,'[]','["ALQAIDA"]',
 'al-qaeda | al qaida | al-qa ida'),

('UN_CONSOLIDATED','un-isis-1','ORGANIZATION','Islamic State',
 '["ISIL","ISIS","Daesh","Da'||chr(39)||'esh"]',
 NULL,'[]','["ISIL-DAESH"]',
 'islamic state | isil | isis | daesh | da esh'),

-- ============================================================
-- HM TREASURY
-- ============================================================
('HM_TREASURY','hmt-putin-1','INDIVIDUAL','Vladimir Putin',
 '["Vladimir Vladimirovich Putin"]',
 '1952-10-07','["ru"]','["RUSSIA"]',
 'vladimir putin | vladimir vladimirovich putin'),

('HM_TREASURY','hmt-lukashenko-1','INDIVIDUAL','Alexander Lukashenko',
 '["Alyaksandr Lukashenka"]',
 '1954-08-30','["by"]','["BELARUS"]',
 'alexander lukashenko | alyaksandr lukashenka'),

('HM_TREASURY','hmt-kim-1','INDIVIDUAL','Kim Jong Un',
 '["Kim Jong-un"]',
 '1984-01-08','["kp"]','["DPRK"]',
 'kim jong un | kim jong-un'),

('HM_TREASURY','hmt-hamas-1','ORGANIZATION','Hamas',
 '["Harakat Al-Muqawama Al-Islamiyya"]',
 NULL,'[]','["TERRORISM"]',
 'hamas | harakat al-muqawama al-islamiyya'),

('HM_TREASURY','hmt-maduro-1','INDIVIDUAL','Nicolas Maduro Moros',
 '["Nicolas Maduro","Maduro"]',
 '1962-11-23','["ve"]','["VENEZUELA"]',
 'nicolas maduro moros | nicolas maduro | maduro'),

('HM_TREASURY','hmt-lavrov-1','INDIVIDUAL','Sergei Lavrov',
 '["Sergey Lavrov","Lavrov"]',
 '1950-03-21','["ru"]','["RUSSIA"]',
 'sergei lavrov | sergey lavrov | lavrov'),

-- ============================================================
-- PEP GLOBAL — politicians, senior officials
-- ============================================================
('PEP_GLOBAL','Q7751569','INDIVIDUAL','Andrej Babiš',
 '["Andrej Babis","Babis","Babiš","A. Babiš","Андрей Бабиш"]',
 '1954-09-02','["cz"]','["PEP"]',
 'andrej babis | babis | babis | a. babis | andrej babis'),

('PEP_GLOBAL','Q131120','INDIVIDUAL','Miloš Zeman',
 '["Milos Zeman","Zeman","M. Zeman"]',
 '1944-09-28','["cz"]','["PEP"]',
 'milos zeman | zeman | m. zeman'),

('PEP_GLOBAL','Q5567893','INDIVIDUAL','Petr Pavel',
 '["Pavel","Petr Pavel","P. Pavel"]',
 '1961-11-01','["cz"]','["PEP"]',
 'petr pavel | pavel | p. pavel'),

('PEP_GLOBAL','Q12750258','INDIVIDUAL','Petr Fiala',
 '["Fiala","Petr Fiala","P. Fiala"]',
 '1964-09-01','["cz"]','["PEP"]',
 'petr fiala | fiala | p. fiala'),

('PEP_GLOBAL','Q17024434','INDIVIDUAL','Alena Schillerová',
 '["Alena Schillerova","Schillerová","Schillerova"]',
 '1964-06-01','["cz"]','["PEP"]',
 'alena schillerova | schillerova | schillerova'),

('PEP_GLOBAL','Q17127','INDIVIDUAL','Viktor Orbán',
 '["Viktor Orban","Orbán","Orban","V. Orbán"]',
 '1963-05-31','["hu"]','["PEP"]',
 'viktor orban | orban | orban | v. orban'),

('PEP_GLOBAL','Q131097','INDIVIDUAL','Robert Fico',
 '["Fico","R. Fico"]',
 '1964-09-15','["sk"]','["PEP"]',
 'robert fico | fico | r. fico'),

('PEP_GLOBAL','Q22686','INDIVIDUAL','Donald Trump',
 '["Donald John Trump","Trump","D. Trump"]',
 '1946-06-14','["us"]','["PEP"]',
 'donald trump | donald john trump | trump | d. trump'),

('PEP_GLOBAL','Q6279','INDIVIDUAL','Joe Biden',
 '["Joseph Robinette Biden","Biden","J. Biden","Joseph Biden"]',
 '1942-11-20','["us"]','["PEP"]',
 'joe biden | joseph robinette biden | biden | j. biden | joseph biden'),

('PEP_GLOBAL','Q3052772','INDIVIDUAL','Emmanuel Macron',
 '["Macron","E. Macron","Emmanuel Jean-Michel Macron"]',
 '1977-12-21','["fr"]','["PEP"]',
 'emmanuel macron | macron | e. macron | emmanuel jean-michel macron'),

('PEP_GLOBAL','Q61095','INDIVIDUAL','Olaf Scholz',
 '["Scholz","O. Scholz"]',
 '1958-06-14','["de"]','["PEP"]',
 'olaf scholz | scholz | o. scholz'),

('PEP_GLOBAL','Q14686','INDIVIDUAL','Boris Johnson',
 '["Boris de Pfeffel Johnson","Johnson","Boris","BJ"]',
 '1964-06-19','["gb"]','["PEP"]',
 'boris johnson | boris de pfeffel johnson | johnson | boris | bj'),

('PEP_GLOBAL','Q6107962','INDIVIDUAL','Rishi Sunak',
 '["Sunak","R. Sunak"]',
 '1980-05-12','["gb"]','["PEP"]',
 'rishi sunak | sunak | r. sunak'),

('PEP_GLOBAL','Q35225','INDIVIDUAL','Xi Jinping',
 '["Jinping","Xi","习近平"]',
 '1953-06-15','["cn"]','["PEP"]',
 'xi jinping | jinping | xi'),

('PEP_GLOBAL','Q1058737','INDIVIDUAL','Narendra Modi',
 '["Modi","N. Modi","Narendra Damodardas Modi"]',
 '1950-09-17','["in"]','["PEP"]',
 'narendra modi | modi | n. modi | narendra damodardas modi'),

('PEP_GLOBAL','Q36258','INDIVIDUAL','Recep Tayyip Erdoğan',
 '["Recep Tayyip Erdogan","Erdogan","Erdoğan","RTE"]',
 '1954-02-26','["tr"]','["PEP"]',
 'recep tayyip erdogan | erdogan | erdogan | rte'),

('PEP_GLOBAL','Q57792','INDIVIDUAL','Benjamin Netanyahu',
 '["Bibi Netanyahu","Netanyahu","Bibi"]',
 '1949-10-21','["il"]','["PEP"]',
 'benjamin netanyahu | bibi netanyahu | netanyahu | bibi'),

('PEP_GLOBAL','Q1268861','INDIVIDUAL','Giorgia Meloni',
 '["Meloni","G. Meloni"]',
 '1977-01-15','["it"]','["PEP"]',
 'giorgia meloni | meloni | g. meloni'),

('PEP_GLOBAL','Q1166030','INDIVIDUAL','Volodymyr Zelenskyy',
 '["Volodymyr Zelensky","Zelensky","Zelenskyy","Зеленський"]',
 '1978-01-25','["ua"]','["PEP"]',
 'volodymyr zelenskyy | volodymyr zelensky | zelensky | zelenskyy'),

('PEP_GLOBAL','pep-vp-1766','INDIVIDUAL','Vladimir Putin',
 '["Vladimir Vladimirovich Putin","Putin"]',
 '1952-10-07','["ru"]','["PEP","HEAD_OF_STATE"]',
 'vladimir putin | vladimir vladimirovich putin | putin'),

('PEP_GLOBAL','Q59626','INDIVIDUAL','Donald Tusk',
 '["Tusk","D. Tusk"]',
 '1957-04-22','["pl"]','["PEP"]',
 'donald tusk | tusk | d. tusk'),

('PEP_GLOBAL','Q30742270','INDIVIDUAL','Ursula von der Leyen',
 '["Ursula von der Leyen","Von der Leyen","VdL"]',
 '1958-10-08','["de"]','["PEP"]',
 'ursula von der leyen | von der leyen | vdl'),

('PEP_GLOBAL','Q10855398','INDIVIDUAL','Christine Lagarde',
 '["Lagarde","C. Lagarde"]',
 '1956-01-01','["fr"]','["PEP"]',
 'christine lagarde | lagarde | c. lagarde'),

('PEP_GLOBAL','Q1263303','INDIVIDUAL','Aleksandar Vucic',
 '["Aleksandar Vučić","Vucic","Vučić"]',
 '1970-03-05','["rs"]','["PEP"]',
 'aleksandar vucic | aleksandar vucic | vucic | vucic'),

-- ============================================================
-- CNB DOMESTIC
-- ============================================================
('CNB_DOMESTIC','cnb-001','ORGANIZATION','Eurocash Finance s.r.o.',
 '["Eurocash Finance"]',
 NULL,'["cz"]','["CNB-SANCTIONS"]',
 'eurocash finance s.r.o. | eurocash finance'),

('CNB_DOMESTIC','cnb-002','INDIVIDUAL','Jan Svoboda',
 '["Svoboda","J. Svoboda"]',
 '1970-03-15','["cz"]','["CNB-SANCTIONS"]',
 'jan svoboda | svoboda | j. svoboda'),

('CNB_DOMESTIC','cnb-003','ORGANIZATION','Crypto Capital s.r.o.',
 '["Crypto Capital"]',
 NULL,'["cz"]','["CNB-SANCTIONS"]',
 'crypto capital s.r.o. | crypto capital')

ON CONFLICT (list_type, external_id) WHERE external_id IS NOT NULL DO NOTHING;

-- Update last_entry_count in sanctions_lists to reflect seeded counts
UPDATE sanctions_lists SET last_entry_count = (
    SELECT COUNT(*) FROM sanctions_entries WHERE list_type = sanctions_lists.list_type AND active = true
), last_updated_at = NOW(), updated_at = NOW()
WHERE list_type IN ('OFAC_SDN','EU_CONSOLIDATED','UN_CONSOLIDATED','HM_TREASURY','PEP_GLOBAL','CNB_DOMESTIC');
