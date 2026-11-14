// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.util.UUID

/**
 * One finding of ongoing monitoring about a subject of an APPROVED case (ADR-0376 D6), with the
 * operator's decision about it. Every finding is decided by itself: a later finding never
 * reopens, replaces or hides an earlier decision.
 *
 * [matchKind], [listType], [matchStatus] and [detection] are the producer's vocabulary kept
 * verbatim as text: a value added upstream must still reach an operator, so nothing here
 * enumerates them. Only `PEP` is read: a PEP match is a customer-risk fact, and every other
 * kind, known or not, is a sanctions-list match — the direction that cannot approve by mistake.
 */
data class MonitoringFinding(
    val findingId: UUID,
    /** The subject of the case it is about; see [ScreeningSubject.key]. */
    val subjectKey: String,
    val matchKind: String,
    val listType: String,
    val listEntryRef: String,
    val listEntryVersion: String,
    val matchScore: Double,
    val matchStatus: String,
    val detection: String,
    val receivedAt: Instant,
    val disposition: FindingDisposition = FindingDisposition.OPEN,
    val decidedBy: String? = null,
    val decisionReason: String? = null,
    val decidedAt: Instant? = null,
) {
    val pep: Boolean get() = matchKind == PEP_KIND

    val open: Boolean get() = disposition == FindingDisposition.OPEN

    /**
     * An operator confirmed that a sanctions-list entry is this subject. That is final, and its
     * only consequence is that the relationship has to end. A confirmed PEP match is a different
     * outcome: a fact about the customer's risk, which ends nothing.
     */
    val requiresExit: Boolean get() = !pep && disposition == FindingDisposition.CONFIRMED

    /** The one and only decision of this finding; a decided finding is never decided again. */
    fun decided(disposition: FindingDisposition, operator: String, reason: String, at: Instant): MonitoringFinding {
        if (!open) throw CaseTransitionException("monitoring finding $findingId is already decided")
        val checked = DispositionRule.checked(disposition, operator, reason)
        return copy(disposition = disposition, decidedBy = operator, decisionReason = checked, decidedAt = at)
    }

    private companion object {
        const val PEP_KIND = "PEP"
    }
}

/** An operator recorded that the exit a confirmed sanctions match requires has been carried out. */
data class ExitCompletion(val completedAt: Instant, val completedBy: String, val evidence: String)

/**
 * Everything ongoing monitoring has said about an approved case and what was decided. It sits
 * BESIDE the case's status and never changes it: the status is what the applicant, the events and
 * every other service read, and none of them may be able to tell that a finding exists.
 *
 * Nothing here is a switch somebody sets. Whether a review is open and whether an exit is owed
 * are read off the findings, so they cannot disagree with them.
 */
data class CaseMonitoring(
    val findings: List<MonitoringFinding> = emptyList(),
    val exitCompleted: ExitCompletion? = null,
) {
    val openFindings: List<MonitoringFinding> get() = findings.filter { it.open }

    /** Since when a finding has been waiting for an operator; null when none is. */
    val reviewSince: Instant? get() = openFindings.minOfOrNull { it.receivedAt }

    /** Since when the relationship has had to end and has not: the first confirmed sanctions match. */
    val exitOwedSince: Instant?
        get() = if (exitCompleted !=
            null
        ) {
            null
        } else {
            findings.filter { it.requiresExit }.mapNotNull { it.decidedAt }.minOrNull()
        }

    /**
     * The case must stay under monitoring whatever its status becomes: a finding nobody decided,
     * or a confirmed match whose exit has not been carried out. Neither the applicant nor a timer
     * can end monitoring while this holds.
     */
    val hold: Boolean get() = openFindings.isNotEmpty() || exitOwedSince != null

    /** Records [finding] once; the SAME instance when its id is already here. */
    fun recorded(finding: MonitoringFinding): CaseMonitoring =
        if (findings.any { it.findingId == finding.findingId }) this else copy(findings = findings + finding)

    fun decided(
        findingId: UUID,
        disposition: FindingDisposition,
        operator: String,
        reason: String,
        at: Instant,
    ): CaseMonitoring {
        val finding = findings.firstOrNull { it.findingId == findingId }
            ?: throw IllegalArgumentException("no monitoring finding $findingId on this case")
        val decided = finding.decided(disposition, operator, reason, at)
        return copy(findings = findings.map { if (it.findingId == findingId) decided else it })
    }

    /**
     * The exit has been carried out. Only a case that owes one can complete it, and every finding
     * must be decided first: an exit does not answer a finding nobody looked at.
     */
    fun exitCompleted(operator: String, evidence: String, at: Instant): CaseMonitoring {
        if (exitOwedSince == null) throw CaseTransitionException("this case owes no exit")
        if (openFindings.isNotEmpty()) {
            throw CaseTransitionException("${openFindings.size} monitoring finding(s) are undecided; decide them first")
        }
        require(operator.isNotBlank()) { "the operator completing the exit must be identified" }
        require(evidence.trim().length >= DispositionRule.MIN_REASON) {
            "completing an exit needs evidence of at least ${DispositionRule.MIN_REASON} characters"
        }
        return copy(exitCompleted = ExitCompletion(at, operator, evidence.trim()))
    }
}

/** The one rule for an operator's decision about a screening finding, at onboarding or from monitoring. */
object DispositionRule {
    const val MIN_REASON = 10

    /** Returns the trimmed reason. */
    fun checked(disposition: FindingDisposition, operator: String, reason: String): String {
        require(disposition != FindingDisposition.OPEN) { "a disposition must be CLEARED_FALSE_POSITIVE or CONFIRMED" }
        require(operator.isNotBlank()) { "the deciding operator must be identified" }
        require(reason.trim().length >= MIN_REASON) {
            "a disposition needs a reason of at least $MIN_REASON characters"
        }
        return reason.trim()
    }
}
