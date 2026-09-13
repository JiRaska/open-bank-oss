-- Rollback: first stop card-issuance consumers, prove all retained legacy events are drained and
-- every producer emits lifecycleRevision, then DROP FUNCTION card_claim_delegation_lifecycle and
-- DROP TABLE card_delegation_lifecycle_cursor. Keep both objects on ordinary code rollback.
CREATE TABLE card_delegation_lifecycle_cursor (
    grant_id UUID PRIMARY KEY,
    lifecycle_revision BIGINT,
    legacy_closed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT card_delegation_cursor_revision_positive
        CHECK (lifecycle_revision IS NULL OR lifecycle_revision > 0)
);

CREATE OR REPLACE FUNCTION card_claim_delegation_lifecycle(
    p_grant_id UUID,
    p_revision BIGINT,
    p_opening BOOLEAN
) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE current_row card_delegation_lifecycle_cursor%ROWTYPE;
BEGIN
    IF p_opening AND p_revision IS NULL THEN RETURN FALSE; END IF;
    INSERT INTO card_delegation_lifecycle_cursor(grant_id, lifecycle_revision, legacy_closed)
    VALUES (p_grant_id, p_revision, NOT p_opening AND p_revision IS NULL)
    ON CONFLICT DO NOTHING;
    IF FOUND THEN RETURN TRUE; END IF;
    SELECT * INTO current_row FROM card_delegation_lifecycle_cursor
      WHERE grant_id = p_grant_id FOR UPDATE;
    IF p_opening THEN
        IF current_row.legacy_closed OR current_row.lifecycle_revision >= p_revision THEN RETURN FALSE; END IF;
    ELSE
        IF p_revision IS NULL THEN
            UPDATE card_delegation_lifecycle_cursor SET legacy_closed = TRUE, updated_at = now()
              WHERE grant_id = p_grant_id;
            RETURN TRUE;
        END IF;
        IF current_row.legacy_closed THEN RETURN TRUE; END IF;
        IF current_row.lifecycle_revision > p_revision THEN RETURN FALSE; END IF;
        IF current_row.lifecycle_revision = p_revision THEN RETURN TRUE; END IF;
    END IF;
    UPDATE card_delegation_lifecycle_cursor SET lifecycle_revision = p_revision, updated_at = now()
      WHERE grant_id = p_grant_id;
    RETURN TRUE;
END $$;
