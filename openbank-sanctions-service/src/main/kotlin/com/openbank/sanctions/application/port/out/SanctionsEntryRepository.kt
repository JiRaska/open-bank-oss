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
     *
     * Idempotent: a row whose content is byte-identical to what is already stored is left
     * untouched (no new tuple version, no WAL) — only `active` is unconditionally forced true, so
     * a previously-deactivated entry that reappears is always reactivated. This matters at the
     * scale of PEP_GLOBAL (~776k rows, barely changing day to day): rewriting the unchanged
     * majority on every refresh generated ~1.9 GB of WAL on a 2Gi volume in a single run (issue
     * #1432), enough by itself to crash the CNPG primary.
     *
     * @return number of rows actually written (inserted, reactivated, or content-changed) — NOT
     *   the number of input entries, since an unchanged row is skipped.
     */
    suspend fun upsertAll(entries: List<SanctionsEntry>): Int

    /**
     * Same upsert as [upsertAll], but returns the `external_id`s of the rows actually written
     * (inserted, reactivated, or content-changed), not just their count. Used to detect *which*
     * entries a refresh changed so a downstream consumer (perpetual KYC re-screening, ADR-0256)
     * can re-screen only the affected customers instead of the whole book.
     *
     * The set is derived from the same `RETURNING` clause that backs the count — so it cannot
     * disagree with what was written. Entries with a null `external_id` are unidentifiable across
     * refreshes and are excluded by construction (the `ON CONFLICT` path requires non-null).
     */
    suspend fun upsertAllReturningChanged(entries: List<SanctionsEntry>): Set<String>

    /**
     * Soft-delete every active entry of [listType] whose `external_id` is NOT in
     * [presentExternalIds] — i.e. it was dropped from the upstream source between this refresh
     * and the last one.
     *
     * Call this ONCE, after the full source has been streamed and upserted — never before. The
     * previous contract (`deactivateByListType`) deactivated the WHOLE list up front, unconditionally,
     * then relied on [upsertAll] to reactivate every row still present: every entry was rewritten
     * TWICE per refresh regardless of whether anything changed, which was half of the WAL cost in
     * #1432. Deactivating first was also a correctness gap — a refresh that failed partway (network
     * drop mid-stream) had already wiped the whole list before the failure, leaving real sanctions
     * entries deactivated with only the partial replacement reactivated. Calling this only after a
     * successful full pass means a failed refresh leaves existing entries untouched (the swallowed
     * exception in [com.openbank.sanctions.application.usecase.SanctionsImportService.importList]
     * never reaches this call), which is the same "keep existing entries" contract that comment
     * already promises.
     *
     * @return number of rows deactivated.
     */
    suspend fun deactivateMissing(listType: SanctionsListType, presentExternalIds: Set<String>): Int

    /**
     * Same deactivation as [deactivateMissing], but returns the `external_id`s of the rows it
     * deactivated, so a downstream consumer (ADR-0256) can re-screen customers that previously
     * matched an entry the upstream source has now dropped.
     */
    suspend fun deactivateMissingReturning(listType: SanctionsListType, presentExternalIds: Set<String>): Set<String>

    suspend fun countByListType(listType: SanctionsListType): Long
}
