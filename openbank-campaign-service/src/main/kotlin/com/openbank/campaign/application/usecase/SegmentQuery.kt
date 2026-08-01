// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentCatalog
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/** A segment as an operator sees it: what it targets, in words, plus its version. */
data class SegmentSummary(val name: String, val version: Int, val rules: List<String>)

/**
 * Cohort size at a point in time.
 *
 * `asOf` is not decoration — ADR-0201 D1 requires that the cohort a marketer previewed and the
 * cohort that is sent to are "the same set or provably a different version". The silver layer moves
 * underneath both, so a preview without a timestamp is a number with no claim attached.
 */
data class SegmentPreview(val name: String, val version: Int, val size: Int, val asOf: Instant)

/**
 * Read side of the segment catalogue, for the operator console (#2895).
 *
 * Deliberately has no create/update: ADR-0201 D1 puts segment definitions in code
 * ([SegmentCatalog]), reviewed and released. "No free-form SQL from a UI" is the point, and an
 * authoring endpoint here would be the same hole in a nicer wrapper.
 */
@ApplicationScoped
class SegmentQuery(private val evaluation: SegmentEvaluationPort, private val clock: Clock) {

    fun list(): List<SegmentSummary> = SegmentCatalog.ALL.map { it.toSummary() }

    /**
     * How many parties this segment currently matches. Runs the real evaluation — the same call
     * enrolment makes — so a preview cannot disagree with the send for any reason other than time.
     */
    suspend fun preview(name: String, version: Int): SegmentPreview? {
        val segment = SegmentCatalog.find(name, version) ?: return null
        return SegmentPreview(
            name = segment.name,
            version = segment.version,
            size = evaluation.evaluate(segment).size,
            asOf = clock.instant(),
        )
    }

    private fun Segment.toSummary() = SegmentSummary(
        name = name,
        version = version,
        rules = rules.map { it.describe() },
    )
}

/**
 * Human phrasing for a rule. Lives here rather than in the domain so the domain keeps no
 * presentation concern; the console translates these further for its own locale.
 */
internal fun com.openbank.campaign.domain.model.SegmentRule.describe(): String = when (this) {
    is com.openbank.campaign.domain.model.SegmentRule.PartyStatusIs -> "party status is $status"
    is com.openbank.campaign.domain.model.SegmentRule.TenureAtLeastDays -> "customer for at least $minDays days"
    is com.openbank.campaign.domain.model.SegmentRule.HasAccount -> "holds an account"
    is com.openbank.campaign.domain.model.SegmentRule.HasActiveConsentScope -> "has an active $scope consent"
}
