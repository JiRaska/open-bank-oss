-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0072 tier-2′ — allow the PROBABILISTIC_CANDIDATE verification trigger.
--
-- The probabilistic (Fellegi-Sunter) tier-2′ matcher opens four-eyes cases with a third
-- trigger value that V6's chk_ivc_trigger CHECK constraint did not permit, so inserting such
-- a case failed with a 23514 constraint violation. Widen the constraint to include it.
-- Rollback: restore the two-value CHECK (only safe once no PROBABILISTIC_CANDIDATE rows exist).

ALTER TABLE identity_verification_case DROP CONSTRAINT chk_ivc_trigger;

ALTER TABLE identity_verification_case ADD CONSTRAINT chk_ivc_trigger CHECK (trigger IN (
    'RN_COLLISION',
    'NAMESAKE_CANDIDATE',
    'PROBABILISTIC_CANDIDATE'
));
