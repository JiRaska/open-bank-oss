-- SPDX-License-Identifier: Apache-2.0
-- Catalog loan terms are evidence for an application: a later product change must not reprice it.
-- Rollback: earlier binaries ignore these nullable additive columns.

ALTER TABLE loan_application
    ADD COLUMN catalog_offering_id UUID,
    ADD COLUMN catalog_revision_id UUID,
    ADD COLUMN catalog_content_hash VARCHAR(64),
    ADD COLUMN catalog_schema_version INTEGER;

CREATE INDEX idx_loan_application_catalog_revision
    ON loan_application(catalog_revision_id)
    WHERE catalog_revision_id IS NOT NULL;
