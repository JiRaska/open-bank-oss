-- The input capture records the exact source revisions selected by a planning run.
-- Rollback (removes captures):
--   DROP FUNCTION capture_planning_inputs(UUID, UUID, UUID, BIGINT[]);
--   DROP TRIGGER validate_planning_input_capture ON planning_input_capture;
--   DROP FUNCTION validate_planning_input_capture();
--   DROP TABLE planning_input_capture_treasury;
--   DROP TABLE planning_input_capture;
--   ALTER TABLE treasury_deal_cash_flow_revision DROP CONSTRAINT uq_treasury_revision_deal;

ALTER TABLE treasury_deal_cash_flow_revision
    ADD CONSTRAINT uq_treasury_revision_deal UNIQUE (deal_id, revision_id);

CREATE TABLE planning_input_capture (
    id               UUID PRIMARY KEY,
    snapshot_id      UUID NOT NULL REFERENCES snapshot_run (id),
    curve_set_id     UUID NOT NULL REFERENCES curve_set (id),
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE planning_input_capture_treasury (
    capture_id   UUID NOT NULL REFERENCES planning_input_capture (id),
    deal_id      UUID NOT NULL,
    revision_id  BIGINT NOT NULL,
    PRIMARY KEY (capture_id, deal_id),
    FOREIGN KEY (deal_id, revision_id)
        REFERENCES treasury_deal_cash_flow_revision (deal_id, revision_id)
);

CREATE FUNCTION validate_planning_input_capture() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM snapshot_run s
        JOIN curve_set c ON c.id = NEW.curve_set_id AND c.as_of = s.as_of
        WHERE s.id = NEW.snapshot_id AND s.status = 'TIED_OUT'
          AND c.provenance = s.provenance
    ) THEN
        RAISE EXCEPTION 'Planning capture requires a tied-out snapshot and same-date curve set with matching provenance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_planning_input_capture
BEFORE INSERT OR UPDATE OF snapshot_id, curve_set_id ON planning_input_capture
FOR EACH ROW EXECUTE FUNCTION validate_planning_input_capture();

-- Calling this function writes the header and all explicitly selected revisions in one statement.
-- A missing revision or two revisions for one deal reject the entire capture.
CREATE FUNCTION capture_planning_inputs(
    capture_key UUID, snapshot_key UUID, curve_set_key UUID, revisions BIGINT[]
) RETURNS UUID LANGUAGE plpgsql AS $$
BEGIN
    IF revisions IS NULL OR array_position(revisions, NULL) IS NOT NULL THEN
        RAISE EXCEPTION 'Provide an explicit array of Treasury revision IDs'
            USING ERRCODE = '23514';
    END IF;

    INSERT INTO planning_input_capture (id, snapshot_id, curve_set_id)
    VALUES (capture_key, snapshot_key, curve_set_key);

    INSERT INTO planning_input_capture_treasury (capture_id, deal_id, revision_id)
    SELECT capture_key, source.deal_id, requested.revision_id
    FROM unnest(revisions) AS requested(revision_id)
    LEFT JOIN treasury_deal_cash_flow_revision source USING (revision_id);

    RETURN capture_key;
END;
$$;
