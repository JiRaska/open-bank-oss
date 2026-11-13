-- SPDX-License-Identifier: Apache-2.0
-- Rollback: DROP TABLE game_completion; DROP TABLE game_catalog_audit; DROP TABLE game_catalog;
CREATE TABLE game_catalog (id VARCHAR(32) PRIMARY KEY CHECK (id = 'lion-quest'), document TEXT NOT NULL);
CREATE TABLE game_catalog_audit (
    revision BIGINT PRIMARY KEY,
    actor VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    before_document TEXT NOT NULL,
    after_document TEXT NOT NULL
);
CREATE TABLE game_completion (
    party_id UUID NOT NULL,
    level_id VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (party_id, level_id)
);
INSERT INTO game_catalog VALUES ('lion-quest', '{"revision":0,"levels":[
 {"id":"embankment","theme":"embankment","order":0,"titleCs":"Náplavka","titleEn":"Embankment","state":"LIVE","requirement":{"kind":"NONE"}},
 {"id":"rooftops","theme":"rooftops","order":1,"titleCs":"Staroměstské střechy","titleEn":"Old Town rooftops","state":"BETA","requirement":{"kind":"PREVIOUS_LEVEL","hintCs":"Dokonči předchozí kolo","hintEn":"Complete the previous chapter"}},
 {"id":"charles_bridge","theme":"charles_bridge","order":2,"titleCs":"Karlův most","titleEn":"Charles Bridge","state":"BETA","requirement":{"kind":"PREVIOUS_LEVEL","hintCs":"Dokonči předchozí kolo","hintEn":"Complete the previous chapter"}}
]}');
