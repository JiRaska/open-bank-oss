// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsEntryMatch
import com.openbank.sanctions.domain.model.SanctionsListType

/** Outbound persistence port for sanctions list entries. */
interface SanctionsEntryRepository {

    /**
     * Fuzzy search across [listTypes] using pg_trgm similarity on the denormalized
     * [SanctionsEntry.searchText] column.  Returns matches ordered by score DESC.
     * @param normalizedQuery  caller must pre-normalize (unaccent + lowercase).
     * @param threshold        minimum similarity score (0.0–1.0); 0.30 is a good default.
     */
    suspend fun search(
        normalizedQuery: String,
        listTypes: List<SanctionsListType>,
        threshold: Double = 0.30,
        limit: Int = 20,
    ): List<SanctionsEntryMatch>

    /**
     * Upsert a batch of entries (insert or update on list_type + external_id).
     * @return number of rows affected.
     */
    suspend fun upsertAll(entries: List<SanctionsEntry>): Int

    /** Soft-delete all entries for a list type (before re-importing). */
    suspend fun deactivateByListType(listType: SanctionsListType): Int

    suspend fun countByListType(listType: SanctionsListType): Long
}
