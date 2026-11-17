-- SPDX-License-Identifier: Apache-2.0
-- Ongoing monitoring of already-screened subjects (ADR-0256 D3, ADR-0376).
--
-- 1. sanctions_monitored_subjects is the registry: one row per subject a caller asked this
--    service to keep watching. It holds exactly the attributes the CUSTOMER screening profile
--    reads (names, date of birth, nationality, country) and no document identifiers. It is
--    personal data: deregistration DELETEs the row, and its names (5) and findings (3) go with it.
--    search_names is the names in the normal form the similarity search receives.
--
-- 2. sanctions_monitoring_pending is the delta queue. It is fed by a trigger on
--    sanctions_change_journal, the table that already records every committed content change
--    of a list entry, so "what changed" has one definition for the change events and for
--    monitoring. The trigger writes nothing while the registry is empty, which is the state of
--    every environment where monitoring is not enabled.
--
-- 3. sanctions_monitoring_findings is one row per (subject, list entry, version of the entry's
--    names). The partial unique index is what makes both the delta and the sweep idempotent.
--    delisted_at is set when the entry leaves its list, so a later relisting raises a new
--    finding instead of being suppressed by the old one.
--
-- 4. sanctions_monitoring_state holds the instant a full re-sweep was last requested (the
--    fallback when a delta is too large to match entry by entry).
--
-- 5. sanctions_monitored_subject_names holds one row per search name of a subject and exists
--    only to index the delta match. The delta is the reverse of the live screen: one list entry
--    against every subject, scored by word_similarity(subject name, entry text). pg_trgm can
--    index only the haystack of that function and here the haystack is the constant, so no
--    operator serves it directly. What can be indexed is a necessary condition. A name with k
--    distinct trigrams that scores t or more shares at least ceil(t * k) of them with the entry
--    text, hence its plain similarity() to that text is at least
--    ceil(t * k) / (k + q - ceil(t * k)), q being the entry's trigram count. That bound depends
--    on k, so the names are split into size classes of similar k, each with its own partial GIN
--    index, and the delta probes each class with `search_name % entry` under the class's own
--    pg_trgm.similarity_threshold. The probe only narrows the candidates: every candidate is
--    then scored by the same word_similarity expression a full scan would use, so the result is
--    the one a full scan gives. The class bounds below are mirrored by MonitoringNameIndex.kt.
--    The CHECK on the name length is what bounds the last class.
--
-- Indexes: the sweep reads never-swept subjects through a partial index and stale ones through
-- (last_swept_at, id), so an idle tick touches a handful of index entries whatever the registry
-- size. The delta match reads the per-class trigram indexes of the names table.
--
-- Rollback: set openbank.sanctions.monitoring.enabled=false, roll back to code that does not
-- read these tables, then DROP TRIGGER sanctions_monitoring_delta ON sanctions_change_journal;
-- DROP FUNCTION sanctions_monitoring_enqueue(); DROP TABLE sanctions_monitoring_findings,
-- sanctions_monitoring_pending, sanctions_monitoring_state, sanctions_monitored_subject_names,
-- sanctions_monitored_subjects.
-- No existing table, column or row is changed, so the previous release keeps working against
-- the migrated schema.

CREATE TABLE sanctions_monitored_subjects (
    id UUID PRIMARY KEY,
    owner_service VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(255) NOT NULL,
    entity_type VARCHAR(20) NOT NULL,
    name TEXT NOT NULL,
    aliases_json TEXT NOT NULL DEFAULT '[]',
    search_names TEXT[] NOT NULL,
    date_of_birth TEXT,
    nationality TEXT,
    country VARCHAR(2),
    registered_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_swept_at TIMESTAMPTZ,
    sweep_claimed_at TIMESTAMPTZ,
    CONSTRAINT uq_sanctions_monitored_subject UNIQUE (owner_service, subject_ref)
);

CREATE INDEX idx_sanctions_monitored_unswept
    ON sanctions_monitored_subjects (id)
    WHERE last_swept_at IS NULL;

CREATE INDEX idx_sanctions_monitored_swept
    ON sanctions_monitored_subjects (last_swept_at, id)
    WHERE last_swept_at IS NOT NULL;

CREATE TABLE sanctions_monitored_subject_names (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES sanctions_monitored_subjects (id) ON DELETE CASCADE,
    search_name TEXT NOT NULL CHECK (char_length(search_name) <= 2000),
    trigram_count INTEGER GENERATED ALWAYS AS (cardinality(show_trgm(search_name))) STORED,
    size_class SMALLINT GENERATED ALWAYS AS (
        width_bucket(cardinality(show_trgm(search_name)),
                     ARRAY[1, 3, 5, 7, 10, 14, 20, 29, 43, 64, 96, 192, 384, 768, 1536])
    ) STORED
);

CREATE INDEX idx_sanctions_monitored_name_subject
    ON sanctions_monitored_subject_names (subject_id);

-- One partial trigram index per size class 1..15. Class 0 is a name without a single trigram,
-- which no entry can match, so it is not indexed.
DO $$
BEGIN
    FOR class IN 1..15 LOOP
        EXECUTE format(
            'CREATE INDEX idx_sanctions_monitored_name_trgm_c%1$s
                 ON sanctions_monitored_subject_names USING gin (search_name gin_trgm_ops)
                 WHERE size_class = %1$s', class);
    END LOOP;
END;
$$;

CREATE TABLE sanctions_monitoring_pending (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id UUID NOT NULL,
    list_type VARCHAR(30) NOT NULL,
    external_id TEXT,
    active BOOLEAN NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claimed_at TIMESTAMPTZ
);

CREATE TABLE sanctions_monitoring_findings (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES sanctions_monitored_subjects (id) ON DELETE CASCADE,
    match_kind VARCHAR(20) NOT NULL,
    list_type VARCHAR(30) NOT NULL,
    list_entry_ref TEXT NOT NULL,
    list_entry_version TEXT NOT NULL,
    match_score DOUBLE PRECISION NOT NULL,
    detection VARCHAR(20) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    delisted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_sanctions_monitoring_finding_open
    ON sanctions_monitoring_findings (subject_id, list_type, list_entry_ref, list_entry_version)
    WHERE delisted_at IS NULL;

CREATE INDEX idx_sanctions_monitoring_finding_entry
    ON sanctions_monitoring_findings (list_type, list_entry_ref)
    WHERE delisted_at IS NULL;

CREATE TABLE sanctions_monitoring_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    resweep_requested_at TIMESTAMPTZ
);
INSERT INTO sanctions_monitoring_state (id) VALUES (1);

CREATE FUNCTION sanctions_monitoring_enqueue() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    -- An empty registry has nothing to match, so list ingestion pays one probe and no write.
    IF EXISTS (SELECT 1 FROM sanctions_monitored_subjects) THEN
        INSERT INTO sanctions_monitoring_pending (entry_id, list_type, external_id, active)
        VALUES (NEW.entry_id, NEW.list_type, NEW.external_id, NEW.active);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sanctions_monitoring_delta
AFTER INSERT ON sanctions_change_journal
FOR EACH ROW EXECUTE FUNCTION sanctions_monitoring_enqueue();
