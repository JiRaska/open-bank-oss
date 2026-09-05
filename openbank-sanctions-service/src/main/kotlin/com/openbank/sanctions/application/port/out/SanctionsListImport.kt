// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

/**
 * Outcome of one sanctions-list import attempt (issue #8362 — the #4348 rule: a skipped, failed
 * or fallback outcome must never share a shape with a working one).
 *
 * Every value below means something an operator must be able to alert on separately:
 *
 *  - [IMPORTED] — the feed was fetched and [ListImportResult.entriesImported] entries upserted;
 *    the stored list now reflects the upstream source as of this run.
 *  - [EMPTY_FEED] — the feed was fetched and parsed successfully but contained zero usable
 *    entries. NOT a success: a sanctions feed that suddenly parses empty is a finding (upstream
 *    format change, wrong URL), and the stored entries were left untouched.
 *  - [FAILED_KEPT_EXISTING] — the fetch/parse failed; the previously stored entries were kept
 *    (fail-safe: a network drop must never wipe the list the screening gate reads).
 *  - [SKIPPED_NOT_ENTITY_BASED] — the list type has no entity feed to import (FATF country-risk,
 *    CNB domestic) — a structural skip, not a failure.
 *  - [SEED_FALLBACK_NON_PRODUCTION] — the service is configured to run on the Flyway-seeded
 *    sample entries (local dev only). The name carries the non-production label so dashboards
 *    and alerts can never confuse it with a real provider import.
 */
enum class ListImportOutcome {
    IMPORTED,
    EMPTY_FEED,
    FAILED_KEPT_EXISTING,
    SKIPPED_NOT_ENTITY_BASED,
    SEED_FALLBACK_NON_PRODUCTION,
}

/** The result of one import attempt: the outcome plus how many entries were upserted. */
data class ListImportResult(val outcome: ListImportOutcome, val entriesImported: Int, val detail: String? = null) {
    init {
        require(entriesImported >= 0) { "entriesImported cannot be negative: $entriesImported" }
        require(outcome != ListImportOutcome.IMPORTED || entriesImported > 0) {
            "IMPORTED requires entriesImported > 0 — a zero-entry feed is EMPTY_FEED, not a success"
        }
    }

    companion object {
        fun imported(count: Int): ListImportResult = ListImportResult(ListImportOutcome.IMPORTED, count)

        fun emptyFeed(): ListImportResult = ListImportResult(ListImportOutcome.EMPTY_FEED, 0)

        fun failedKeptExisting(detail: String): ListImportResult =
            ListImportResult(ListImportOutcome.FAILED_KEPT_EXISTING, 0, detail)

        fun skippedNotEntityBased(detail: String): ListImportResult =
            ListImportResult(ListImportOutcome.SKIPPED_NOT_ENTITY_BASED, 0, detail)

        fun seedFallback(detail: String): ListImportResult =
            ListImportResult(ListImportOutcome.SEED_FALLBACK_NON_PRODUCTION, 0, detail)
    }
}
