-- Erasure of a party (GDPR Art. 17, the PARTY_ERASED event) anonymises that party's AML cases:
-- customer_reference becomes ERASED-<partyId>, matched_entity and alert_detail are cleared, and
-- the row itself stays, because AML record-keeping outlives the erasure. That rule is unchanged.
--
-- What it could not do is reach a case that does not name the party yet. Since V14 a case may be
-- opened with no party (a held payment whose rail could not name the debtor) and be attached to
-- its party later by the resolution sweep; before V14 the same was true of cases carrying the
-- account id where the party belongs. A party erased in between was found by nothing: the erasure
-- matched on party_id and passed the case by, and the sweep then attached the case, personal data
-- intact, to a party that had been erased.
--
-- This table records that a party WAS erased, so the sweep can anonymise a case in the same
-- transaction that attaches it. It holds the party id and the time and nothing else: the id is the
-- pseudonymous reference every anonymised case already keeps in ERASED-<partyId>, so the table
-- adds no personal data to what erasure leaves behind.
--
-- Rolling deploy: a pod from before this migration neither reads nor writes the table. While such
-- a pod is still consuming, an erasure it handles is not recorded; a redelivery of PARTY_ERASED to
-- a new pod records it, and the anonymisation itself is idempotent.
--
-- Rollback: roll the code back first (the erasure and the sweep both use the table), then
--   DROP TABLE aml_erased_party;
-- and delete this file's row from flyway_schema_history. Data lost on rollback: the record of
-- which parties were erased, and with it the anonymisation of cases resolved to them afterwards.
-- No case row is touched by the rollback.
CREATE TABLE aml_erased_party (
    party_id  UUID        PRIMARY KEY,
    erased_at TIMESTAMPTZ NOT NULL
);
