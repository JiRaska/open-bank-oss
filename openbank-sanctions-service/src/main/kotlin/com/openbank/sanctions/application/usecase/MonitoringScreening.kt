// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * What ongoing monitoring asks of the live screen (ADR-0376).
 *
 * Every answer comes from [SanctionsService]'s own evaluation and clearance rule, never from a
 * restatement of them, so monitoring cannot drift from a live screen on thresholds, list sets or
 * what a clearance covers.
 */
@ApplicationScoped
class MonitoringScreening(private val screening: SanctionsService, private val clock: Clock) {

    /**
     * The matches a fresh CUSTOMER screen of [cmd] would report that no clearance covers:
     * jurisdiction and PEP matches always, name matches unless a clearance covers them all.
     */
    suspend fun uncoveredMatches(cmd: ScreenEntityCommand): List<SanctionsMatch> {
        val evaluation = screening.evaluate(cmd)
        val pep = evaluation.outcome.matches.filter { it.matchKind == MatchKind.PEP }
        val names = if (evaluation.nameClearance == null) evaluation.clearable else emptyList()
        return evaluation.jurisdiction + names + pep
    }

    /** One list entry as the CUSTOMER profile would report it for a subject scoring [score]. */
    fun customerMatch(
        listType: SanctionsListType,
        externalId: String?,
        primaryName: String,
        programs: List<String>,
        score: Double,
    ): SanctionsMatch =
        nameMatch(listType, externalId, primaryName, programs, score).copy(matchKind = MatchKind.ofList(listType))

    /** Whether an unexpired clearance of the same subject covers [match], by the live screen's rule. */
    suspend fun isCleared(cmd: ScreenEntityCommand, match: SanctionsMatch): Boolean =
        screening.findCoveringClearance(cmd, listOf(match), Instant.now(clock)) != null
}
