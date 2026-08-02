// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

/**
 * The segment catalogue — ADR-0201 D1's "a segment is a versioned artifact, not a query".
 *
 * Segments are declared HERE, in code, reviewed and released like anything else. Before this, the
 * registry read a database table that nothing in the codebase ever wrote to: `SegmentRegistry.save`
 * had no caller, so the only way a segment could exist was a hand-written INSERT. That is precisely
 * the unversioned path D1 forbids, and it made "the cohort a marketer previewed and the cohort that
 * was sent to are the same set or provably a different version" unenforceable — a row could change
 * under a running campaign with no version bump and no trace.
 *
 * Adding or changing a segment is therefore a PR: a new entry, or a new `version` of an existing
 * name. **Never edit a released version in place** — a campaign references `name@version`, and
 * changing what that resolves to silently redefines who an already-approved campaign will reach.
 */
object SegmentCatalog {

    /** Part of the `actives-tenured-30d` segment's identity — changing it redefines who it reaches. */
    private const val TENURE_30D = 30L

    /**
     * Every segment the fleet can target. Keyed by (name, version); a name may have several
     * versions and campaigns pin the one they were approved against.
     */
    val ALL: List<Segment> = listOf(
        Segment(
            name = "actives",
            version = 1,
            rules = listOf(SegmentRule.PartyStatusIs("ACTIVE")),
        ),
        Segment(
            name = "actives-tenured-30d",
            version = 1,
            rules = listOf(
                SegmentRule.PartyStatusIs("ACTIVE"),
                SegmentRule.TenureAtLeastDays(TENURE_30D),
            ),
        ),
    )

    fun find(name: String, version: Int): Segment? = ALL.firstOrNull { it.name == name && it.version == version }
}
