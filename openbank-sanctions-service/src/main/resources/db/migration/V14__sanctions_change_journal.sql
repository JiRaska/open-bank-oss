-- SPDX-License-Identifier: Apache-2.0
-- Every committed screening-content mutation retains evidence until an outbox transaction
-- publishes it. The trigger also covers PgPool batches and writers from a rolling deployment.
-- Rollback: stop the publisher, archive pending rows, then drop the trigger, its functions,
-- sanctions_change_journal and sanctions_change_publication. Do not discard pending evidence.

CREATE TABLE sanctions_change_journal (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id UUID NOT NULL,
    list_type VARCHAR(30) NOT NULL,
    external_id TEXT,
    active BOOLEAN NOT NULL,
    previously_active BOOLEAN NOT NULL,
    existed_before BOOLEAN NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at TIMESTAMPTZ,
    resolved_by_list_type VARCHAR(30),
    resolved_by_external_id TEXT
);
CREATE INDEX idx_sanctions_change_journal_list ON sanctions_change_journal (list_type, id);

-- Only publishers take this lock; entry writers append independently to the journal.
CREATE TABLE sanctions_change_publication (
    list_type VARCHAR(30) PRIMARY KEY,
    last_storm_fingerprint TEXT
);
INSERT INTO sanctions_change_publication (list_type) SELECT list_type FROM sanctions_lists;

CREATE FUNCTION sanctions_canonical_values(raw TEXT) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    parsed JSONB;
    normalized JSONB;
BEGIN
    parsed := raw::jsonb;
    -- These legacy columns are TEXT, not constrained JSON arrays. An existing malformed
    -- value must remain repairable and must not prevent deactivation of a screening entry.
    IF jsonb_typeof(parsed) IS DISTINCT FROM 'array' THEN
        RETURN jsonb_build_object('raw', raw);
    END IF;
    SELECT COALESCE(jsonb_agg(value ORDER BY value), '[]'::jsonb) INTO normalized
    FROM (SELECT DISTINCT value FROM jsonb_array_elements(parsed)) AS valueset;
    RETURN jsonb_build_object('values', normalized);
EXCEPTION WHEN data_exception THEN
    RETURN jsonb_build_object('raw', raw);
END;
$$;

CREATE FUNCTION sanctions_record_change() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(OLD.list_type, OLD.external_id, OLD.entity_type, OLD.primary_name,
               sanctions_canonical_values(OLD.aliases_json), OLD.date_of_birth,
               sanctions_canonical_values(OLD.nationalities), sanctions_canonical_values(OLD.programs),
               sanctions_canonical_values(to_jsonb(string_to_array(OLD.search_text, ' | '))::text), OLD.active)
           IS NOT DISTINCT FROM
           ROW(NEW.list_type, NEW.external_id, NEW.entity_type, NEW.primary_name,
               sanctions_canonical_values(NEW.aliases_json), NEW.date_of_birth,
               sanctions_canonical_values(NEW.nationalities), sanctions_canonical_values(NEW.programs),
               sanctions_canonical_values(to_jsonb(string_to_array(NEW.search_text, ' | '))::text), NEW.active)
        THEN
            RETURN NEW;
        END IF;
        -- A changed source identity affects both the old and the new targeting key.
        IF ROW(OLD.list_type, OLD.external_id) IS DISTINCT FROM ROW(NEW.list_type, NEW.external_id) THEN
            INSERT INTO sanctions_change_journal
                (entry_id, list_type, external_id, active, previously_active, existed_before)
            VALUES (OLD.id, OLD.list_type, OLD.external_id, false, OLD.active, true);
            INSERT INTO sanctions_change_journal
                (entry_id, list_type, external_id, active, previously_active, existed_before)
            VALUES (NEW.id, NEW.list_type, NEW.external_id, NEW.active, false, false);
            RETURN NEW;
        END IF;
        INSERT INTO sanctions_change_journal
            (entry_id, list_type, external_id, active, previously_active, existed_before)
        VALUES (NEW.id, NEW.list_type, NEW.external_id, NEW.active, OLD.active, true);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO sanctions_change_journal
            (entry_id, list_type, external_id, active, previously_active, existed_before)
        VALUES (OLD.id, OLD.list_type, OLD.external_id, false, OLD.active, true);
        RETURN OLD;
    END IF;
    INSERT INTO sanctions_change_journal
        (entry_id, list_type, external_id, active, previously_active, existed_before)
    VALUES (NEW.id, NEW.list_type, NEW.external_id, NEW.active, false, false);
    RETURN NEW;
END;
$$;

CREATE TRIGGER sanctions_entry_change_journal
AFTER INSERT OR UPDATE OR DELETE ON sanctions_entries
FOR EACH ROW EXECUTE FUNCTION sanctions_record_change();
