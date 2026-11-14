-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- The successor of an OVERTURNED decline review points back at the declined application it replaces
-- (loan_decline_review.successor_application_id is the forward link).
-- Rollback: ALTER TABLE loan_application DROP COLUMN predecessor_application_id. Loses the back link on
-- successors already created; the forward link on loan_decline_review is kept.
ALTER TABLE loan_application ADD COLUMN predecessor_application_id UUID REFERENCES loan_application (id);
