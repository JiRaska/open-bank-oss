-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Serialize normalized child mutations with publication of their parent revision. Rollback: stop
-- catalog writers, restore the V6 function body, then restart writers. Never roll this back while
-- a revision can be published because the earlier trigger has a child-insert/publish TOCTOU window.

CREATE OR REPLACE FUNCTION enforce_published_catalog_child_immutability() RETURNS TRIGGER AS $$
DECLARE
    old_owner_state VARCHAR(16);
    new_owner_state VARCHAR(16);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        PERFORM 1 FROM catalog_revisions
         WHERE id IN (OLD.revision_id, NEW.revision_id)
         ORDER BY id
         FOR UPDATE;
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM catalog_revisions WHERE id = OLD.revision_id FOR UPDATE;
    ELSE
        PERFORM 1 FROM catalog_revisions WHERE id = NEW.revision_id FOR UPDATE;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT state INTO old_owner_state FROM catalog_revisions WHERE id = OLD.revision_id;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT state INTO new_owner_state FROM catalog_revisions WHERE id = NEW.revision_id;
    END IF;
    IF old_owner_state IN ('PUBLISHED', 'SUPERSEDED')
        OR new_owner_state IN ('PUBLISHED', 'SUPERSEDED')
    THEN
        RAISE EXCEPTION 'published catalog revision child is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
