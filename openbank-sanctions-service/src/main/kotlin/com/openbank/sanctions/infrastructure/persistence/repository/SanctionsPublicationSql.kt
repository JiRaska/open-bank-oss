// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

/** Transaction-local working sets keep fleet-sized journals out of the application heap. */
internal object SanctionsPublicationSql {
    const val CREATE_SELECTION = """
        CREATE TEMP TABLE sanctions_publication_selection (id BIGINT PRIMARY KEY) ON COMMIT DROP
    """
    const val RESOLVE_REPAIRED_IDENTITIES = """
        UPDATE sanctions_change_journal j
        SET resolved_at = clock_timestamp(), resolved_by_list_type = e.list_type,
            resolved_by_external_id = e.external_id
        FROM sanctions_entries e
        WHERE j.list_type = :type AND j.resolved_at IS NULL
          AND j.entry_id = e.id
          AND (j.external_id IS NULL OR octet_length(to_json(j.external_id)::text) > :maxBytes)
          AND e.external_id IS NOT NULL
          AND octet_length(to_json(e.external_id)::text) <= :maxBytes
          AND j.external_id IS DISTINCT FROM e.external_id
    """
    const val SELECT_PENDING = """
        INSERT INTO sanctions_publication_selection (id)
        SELECT id FROM sanctions_change_journal
        WHERE list_type = :type AND resolved_at IS NULL ORDER BY id FOR UPDATE
    """
    const val CREATE_CHANGES = """
        CREATE TEMP TABLE sanctions_publication_changes ON COMMIT DROP AS
        SELECT DISTINCT ON (entry_id, external_id) entry_id, external_id, j.id, active,
               first_value(previously_active) OVER (
                   PARTITION BY entry_id, external_id ORDER BY j.id
               ) AS previously_active
        FROM sanctions_change_journal j JOIN sanctions_publication_selection s ON s.id = j.id
        ORDER BY entry_id, external_id, j.id DESC
    """
    const val CREATE_TARGETS = """
        CREATE TEMP TABLE sanctions_publication_targets ON COMMIT DROP AS
        SELECT DISTINCT ON (external_id, CASE WHEN external_id IS NULL THEN entry_id END)
               id, external_id, active
        FROM sanctions_publication_changes
        ORDER BY external_id, CASE WHEN external_id IS NULL THEN entry_id END, id DESC
    """
    const val SUMMARY = """
        SELECT json_build_object(
            'changeCount', (SELECT count(*) FROM sanctions_publication_targets),
            'baseline', GREATEST(0,
                (SELECT count(*) FROM sanctions_entries WHERE list_type = :type AND active) -
                (SELECT COALESCE(sum(active::int - previously_active::int), 0) FROM sanctions_publication_changes)),
            'missingIdentity', (SELECT EXISTS(SELECT 1 FROM sanctions_publication_targets WHERE external_id IS NULL)),
            'oversizedIdentity', (SELECT EXISTS(SELECT 1 FROM sanctions_publication_targets
                                               WHERE octet_length(to_json(external_id)::text) > :maxBytes)),
            'selectedCount', (SELECT count(*) FROM sanctions_publication_selection),
            'maximumId', (SELECT max(id) FROM sanctions_publication_selection)
        )::text
    """
    const val TARGET_PAGE = """
        SELECT json_build_object('id', id, 'external_id', external_id, 'active', active)::text
        FROM sanctions_publication_targets WHERE id > :after ORDER BY id LIMIT :pageSize
    """
    const val DELETE_SELECTION = """
        DELETE FROM sanctions_change_journal j USING sanctions_publication_selection s WHERE j.id = s.id
    """
}
