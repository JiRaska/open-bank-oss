-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- An offering has at most ONE open draft: every reader (the v2 publish flow and the bank-v1
-- compatibility projector) treats "the draft" as singular, and createDraft never checked. A
-- double-submitted create therefore left two drafts that no reader could ever choose between.
--
-- Step 1 discards only EXACT duplicates: drafts of the same offering whose schema, content,
-- effective window and maker equal the newest draft's, and which own no child rows and are not a
-- bank-v1 projection target. The newest draft (highest revision_no) survives. Each discard is
-- recorded in the append-only catalog_audit. Any other multi-draft offering is ambiguous, so the
-- migration fails closed and names it; an operator must publish or delete one draft first.
--
-- Rollback: DROP INDEX uq_catalog_revisions_single_draft; — safe at any time, it only removes the
-- guard. Discarded duplicates are not restored (they were byte-identical to the surviving draft;
-- their ids remain in catalog_audit with action REVISION_DUPLICATE_DISCARDED).

DO $$
DECLARE
    ambiguous TEXT;
BEGIN
    CREATE TEMP TABLE duplicate_drafts ON COMMIT DROP AS
    SELECT loser.id, loser.offering_id, keeper.id AS kept_revision_id
      FROM catalog_revisions loser
      JOIN LATERAL (
          SELECT k.*
            FROM catalog_revisions k
           WHERE k.offering_id = loser.offering_id AND k.state = 'DRAFT'
           ORDER BY k.revision_no DESC
           LIMIT 1
      ) keeper ON keeper.id <> loser.id
     WHERE loser.state = 'DRAFT'
       AND loser.schema_id = keeper.schema_id
       AND loser.schema_version = keeper.schema_version
       AND loser.content = keeper.content
       AND loser.effective_from IS NOT DISTINCT FROM keeper.effective_from
       AND loser.effective_to IS NOT DISTINCT FROM keeper.effective_to
       AND loser.maker_id = keeper.maker_id
       AND NOT EXISTS (SELECT 1 FROM catalog_price_components c WHERE c.revision_id = loser.id)
       AND NOT EXISTS (SELECT 1 FROM catalog_relationships r WHERE r.revision_id = loser.id)
       AND NOT EXISTS (SELECT 1 FROM bank_v1_product_mapping m WHERE m.projected_revision_id = loser.id);

    INSERT INTO catalog_audit (id, aggregate_type, aggregate_id, action, actor_id, occurred_at, details)
    SELECT gen_random_uuid(), 'REVISION', d.id, 'REVISION_DUPLICATE_DISCARDED', 'system:migration-v11', now(),
           jsonb_build_object('offeringId', d.offering_id, 'keptRevisionId', d.kept_revision_id)
      FROM duplicate_drafts d;

    DELETE FROM catalog_revisions r USING duplicate_drafts d WHERE r.id = d.id;

    SELECT string_agg(offering_id::text, ', ') INTO ambiguous
      FROM (SELECT offering_id FROM catalog_revisions WHERE state = 'DRAFT'
             GROUP BY offering_id HAVING count(*) > 1) multi;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'offerings with divergent open drafts need manual resolution: %', ambiguous
            USING ERRCODE = '23505';
    END IF;
END;
$$;

CREATE UNIQUE INDEX uq_catalog_revisions_single_draft
    ON catalog_revisions (offering_id)
    WHERE state = 'DRAFT';
