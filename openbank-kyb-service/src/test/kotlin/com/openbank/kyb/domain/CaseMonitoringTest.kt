// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.ExitProposal
import com.openbank.kyb.domain.model.FindingDisposition
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.MonitoringFinding
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.UboSource
import com.openbank.libs.governance.StaffIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The aggregate's side of ongoing monitoring: who is to be registered, what a finding does to an
 * approved case (nothing an applicant can read), how each finding is decided by itself, and what
 * ends monitoring.
 */
class CaseMonitoringTest {

    private val t0: Instant = Instant.parse("2026-11-13T09:00:00Z")
    private val t1: Instant = t0.plusSeconds(3600)
    private val entity = "k-entity"
    private val person = "k-person"

    private fun subject(key: String, kind: ScreeningSubjectKind, current: Boolean = true) = ScreeningSubject(
        key = key,
        kind = kind,
        name = "Invented $key",
        organisation = kind == ScreeningSubjectKind.ENTITY,
        sanctions = ScreeningVerdict.CLEAR,
        current = current,
    )

    private fun screening() = CaseScreening(
        listOf(subject(entity, ScreeningSubjectKind.ENTITY), subject(person, ScreeningSubjectKind.REPRESENTATIVE)),
        UboSource.REGISTER,
    )

    private fun case(status: CaseStatus, screening: CaseScreening? = screening()) = BusinessOnboardingCase(
        id = UUID.fromString("0190a000-0000-7000-8000-0000000000c1"),
        identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
        initiatorPartyId = UUID.fromString("0190a000-0000-7000-8000-0000000000b1"),
        status = status,
        extract = null,
        entityPartyId = UUID.fromString("0190a000-0000-7000-8000-000000000001"),
        requiredSignatures = 1,
        signers = emptyList(),
        reviewReason = null,
        screening = screening,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun finding(n: Int, subjectKey: String = person, kind: String = "SANCTIONS") = MonitoringFinding(
        findingId = UUID.fromString("0190a000-0000-7000-8000-0000000000f$n"),
        subjectKey = subjectKey,
        matchKind = kind,
        listType = "EU_CONSOLIDATED",
        listEntryRef = "id:synthetic-$n",
        listEntryVersion = "v1",
        matchScore = 0.91,
        matchStatus = "POTENTIAL_HIT",
        detection = "LIST_DELTA",
        receivedAt = t1.plusSeconds(n.toLong()),
    )

    private fun BusinessOnboardingCase.decide(
        n: Int,
        disposition: FindingDisposition,
        at: Instant = t1.plusSeconds(100),
    ) = monitoringFindingDecided(finding(n).findingId, disposition, "operator-1", "decided in a unit test", at)

    @Test
    fun `only an approved case is monitored, and only its current subjects`() {
        val withLeaver = screening().copy(
            subjects = screening().subjects + subject("k-left", ScreeningSubjectKind.BENEFICIAL_OWNER, current = false),
        )
        assertThat(case(CaseStatus.SIGNED, withLeaver).monitoredSubjects.map { it.key }).containsExactly(entity, person)
        assertThat(case(CaseStatus.ACTIVE).monitoredSubjects).hasSize(2)
        listOf(
            CaseStatus.READY_TO_SIGN,
            CaseStatus.AWAITING_COSIGNERS,
            CaseStatus.MANUAL_REVIEW,
            CaseStatus.REJECTED,
            CaseStatus.ABANDONED,
        ).forEach { assertThat(case(it).monitoredSubjects).describedAs(it.name).isEmpty() }
        assertThat(case(CaseStatus.ACTIVE, screening = null).monitoredSubjects).isEmpty()
        // The registration store is touched only for a case that is or was approved.
        assertThat(CaseStatus.entries.filter { case(it).everApproved }).containsExactlyInAnyOrder(
            CaseStatus.SIGNED,
            CaseStatus.ACTIVE,
            CaseStatus.REJECTED,
            CaseStatus.ABANDONED,
        )
    }

    @Test
    fun `a finding is recorded beside the case and changes nothing else about it`() {
        val active = case(CaseStatus.ACTIVE)

        val found = active.monitoringMatched(finding(1))

        // Everything an applicant, an event or another service reads is exactly what it was.
        assertThat(found.copy(monitoring = active.monitoring)).isEqualTo(active)
        assertThat(found.monitoring.findings).containsExactly(finding(1))
        assertThat(found.monitoring.reviewSince).isEqualTo(finding(1).receivedAt)
        assertThat(found.monitoring.exitOwedSince).isNull()
        assertThat(found.screening).describedAs("the onboarding screening is not rewritten").isEqualTo(active.screening)
    }

    @Test
    fun `the same finding id twice writes nothing`() {
        val found = case(CaseStatus.SIGNED).monitoringMatched(finding(1))

        assertThat(found.monitoringMatched(finding(1).copy(matchScore = 0.5))).isSameAs(found)
    }

    @Test
    fun `a finding is refused for a case that is not monitored and for a subject it does not carry`() {
        assertThatThrownBy { case(CaseStatus.READY_TO_SIGN).monitoringMatched(finding(1)) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { case(CaseStatus.REJECTED).monitoringMatched(finding(1)) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { case(CaseStatus.ACTIVE).monitoringMatched(finding(1, subjectKey = "k-nobody")) }
            .isInstanceOf(CaseTransitionException::class.java)
    }

    @Test
    fun `each finding is decided by itself - a later one neither reopens nor replaces an earlier decision`() {
        val first = case(CaseStatus.ACTIVE).monitoringMatched(finding(1)).decide(1, FindingDisposition.CONFIRMED)
        assertThat(first.monitoring.exitOwedSince).isEqualTo(t1.plusSeconds(100))

        // A second finding on the SAME subject arrives and is cleared.
        val second = first.monitoringMatched(finding(2)).decide(2, FindingDisposition.CLEARED_FALSE_POSITIVE)

        val decisions = second.monitoring.findings.associate { it.listEntryRef to it.disposition }
        assertThat(decisions).containsEntry("id:synthetic-1", FindingDisposition.CONFIRMED)
            .containsEntry("id:synthetic-2", FindingDisposition.CLEARED_FALSE_POSITIVE)
        // The confirmed sanctions match stands: clearing the later finding clears nothing else.
        assertThat(second.monitoring.exitOwedSince).isEqualTo(t1.plusSeconds(100))
        assertThat(second.monitoring.hold).isTrue()
        // And a decision is made once.
        assertThatThrownBy { second.decide(1, FindingDisposition.CLEARED_FALSE_POSITIVE) }
            .isInstanceOf(CaseTransitionException::class.java)
    }

    @Test
    fun `a confirmed PEP match is a risk fact and requires no exit, a confirmed sanctions match does`() {
        val pep = case(
            CaseStatus.ACTIVE,
        ).monitoringMatched(finding(1, kind = "PEP")).decide(1, FindingDisposition.CONFIRMED)
        assertThat(pep.monitoring.exitOwedSince).isNull()
        assertThat(pep.monitoring.hold).describedAs("everything is decided and nothing has to end").isFalse()

        // A kind this service does not know is read as a sanctions match: the safe direction.
        val unknown = case(CaseStatus.ACTIVE).monitoringMatched(finding(2, kind = "SOMETHING_NEW"))
            .decide(2, FindingDisposition.CONFIRMED)
        assertThat(unknown.monitoring.exitOwedSince).isNotNull()

        // A cleared sanctions match beside a confirmed PEP one does NOT read as confirmed sanctions.
        val mixed = case(CaseStatus.ACTIVE)
            .monitoringMatched(finding(3)).decide(3, FindingDisposition.CLEARED_FALSE_POSITIVE)
            .monitoringMatched(finding(4, kind = "PEP")).decide(4, FindingDisposition.CONFIRMED)
        assertThat(mixed.monitoring.exitOwedSince).isNull()
    }

    @Test
    fun `a decision needs a real disposition, an operator and a reason - the same rule as at onboarding`() {
        val found = case(CaseStatus.ACTIVE).monitoringMatched(finding(1))
        val id = finding(1).findingId

        assertThatThrownBy {
            found.monitoringFindingDecided(id, FindingDisposition.OPEN, "op", "long enough reason", t1)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            found.monitoringFindingDecided(id, FindingDisposition.CONFIRMED, " ", "long enough reason", t1)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { found.monitoringFindingDecided(id, FindingDisposition.CONFIRMED, "op", "short", t1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            found.monitoringFindingDecided(
                UUID.randomUUID(),
                FindingDisposition.CONFIRMED,
                "op",
                "long enough reason",
                t1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `neither the applicant, a timer nor a rejection ends monitoring while a finding is open or an exit is owed`() {
        val open = case(CaseStatus.SIGNED).monitoringMatched(finding(1))

        // The status changes exactly as it always did; monitoring does not follow it.
        val abandoned = open.abandoned(t1)
        assertThat(abandoned.status).isEqualTo(CaseStatus.ABANDONED)
        assertThat(abandoned.monitored).isTrue()
        assertThat(abandoned.monitoredSubjects).hasSize(2)
        assertThat(open.rejected("rejected by an operator", t1).monitored).isTrue()

        // Decided clean: nothing holds the ended case any more, and its subjects are withdrawn.
        val clean = abandoned.decide(1, FindingDisposition.CLEARED_FALSE_POSITIVE)
        assertThat(clean.monitored).isFalse()
        assertThat(clean.monitoredSubjects).isEmpty()

        // Confirmed: still monitored, whatever the status, until the exit is recorded.
        val confirmed = abandoned.decide(1, FindingDisposition.CONFIRMED)
        assertThat(confirmed.monitored).isTrue()
        // Control: without any finding, abandoning ends monitoring at once.
        assertThat(case(CaseStatus.SIGNED).abandoned(t1).monitored).isFalse()
    }

    /** An owed exit, every finding decided, on a case kyb no longer treats as approved. */
    private fun owedOn(status: CaseStatus): BusinessOnboardingCase =
        case(CaseStatus.SIGNED).monitoringMatched(finding(1)).decide(1, FindingDisposition.CONFIRMED).let {
            when (status) {
                CaseStatus.REJECTED -> it.rejected("rejected by an operator", t1)
                CaseStatus.ABANDONED -> it.abandoned(t1)
                else -> it
            }
        }

    private val evidence = "party closed, mandates revoked, accounts closed: ticket SYN-1"

    // reviewerA and reviewerA2 are two accounts of ONE person.
    private val reviewerA = StaffIdentity("reviewer-a", "staff-a", strongAuth = true)
    private val reviewerA2 = StaffIdentity("reviewer-a.second", "staff-a", strongAuth = true)
    private val reviewerB = StaffIdentity("reviewer-b", "staff-b", strongAuth = true)
    private val reviewerC = StaffIdentity("reviewer-c", "staff-c", strongAuth = true)

    @Test
    fun `an exit proposal of the V15 shape is inert - replaceable by anyone, never confirmable`() {
        val owed = owedOn(CaseStatus.REJECTED)
        for (legacy in listOf(
            ExitProposal(t1, "legacy", evidence),
            ExitProposal(t1, "legacy", evidence, proposerPersonId = "staff-x"),
            ExitProposal(t1, "legacy", evidence, expiresAt = t1.plusSeconds(3600)),
        )) {
            val stuck = owed.copy(monitoring = owed.monitoring.copy(exitProposal = legacy))
            assertThat(legacy.isOpenAt(t1)).isFalse()
            assertThatThrownBy { stuck.exitConfirmed(reviewerB, evidence, true, t1) }
                .isInstanceOf(CaseTransitionException::class.java)
            val fresh = stuck.exitProposed(reviewerC, evidence, true, t1)
            assertThat(fresh.exitConfirmed(reviewerB, evidence, true, t1).monitored).isFalse()
        }
    }

    @Test
    fun `the exit four-eyes compares persons, needs a second factor, and the proposer may withdraw`() {
        val owed = owedOn(CaseStatus.REJECTED)
        val proposed = owed.exitProposed(reviewerA, evidence, true, t1)
        assertThat(proposed.monitoring.exitProposal?.proposerPersonId).isEqualTo("staff-a")
        assertThat(proposed.monitoring.exitProposal?.expiresAt).isEqualTo(t1.plus(ExitProposal.TTL))
        // The proposer's second account is the same person.
        assertThatThrownBy { proposed.exitConfirmed(reviewerA2, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java).hasMessageContaining("same person")
        // No person id, no second factor: refused.
        assertThatThrownBy { proposed.exitConfirmed(StaffIdentity("svc", null, strongAuth = true), evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { proposed.exitConfirmed(reviewerB.copy(strongAuth = false), evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java).hasMessageContaining("second factor")
        assertThatThrownBy { owed.exitProposed(StaffIdentity("svc", null, strongAuth = true), evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        // A pending proposal is not replaced by another reviewer, nor withdrawn by one.
        assertThatThrownBy { proposed.exitProposed(reviewerC, evidence + " (c)", true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { proposed.exitWithdrawn(reviewerC, t1) }.isInstanceOf(CaseTransitionException::class.java)
        // The proposer withdraws from their other account; then it cannot be confirmed, and anyone may propose.
        val withdrawn = proposed.exitWithdrawn(reviewerA2, t1)
        assertThat(withdrawn.monitoring.exitProposal?.withdrawnBy).isEqualTo("reviewer-a.second")
        assertThatThrownBy { withdrawn.exitConfirmed(reviewerB, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        val again = withdrawn.exitProposed(reviewerC, evidence, true, t1)
        assertThat(again.exitConfirmed(reviewerB, evidence, true, t1).monitored).isFalse()
        // Expiry: no confirmation once it has run out, and a replacement is allowed.
        val late = t1.plus(ExitProposal.TTL)
        assertThatThrownBy { proposed.exitConfirmed(reviewerB, evidence, true, late) }
            .isInstanceOf(CaseTransitionException::class.java).hasMessageContaining("expired")
        assertThat(proposed.exitProposed(reviewerC, evidence, true, late).monitoring.exitProposal?.proposedBy)
            .isEqualTo("reviewer-c")
    }

    @Test
    fun `an exit is recorded only on a four-eyes confirmation, and monitoring continues until then`() {
        val owed = owedOn(CaseStatus.REJECTED)
        val late = owed.monitoringMatched(finding(2))
        assertThat(late.monitoring.findings).describedAs("a finding after the exit became owed is recorded").hasSize(2)

        // Must-refuse: an undecided finding, too little evidence, nobody named.
        assertThatThrownBy { late.exitProposed(reviewerA, evidence, entityPartyClosed = true, at = t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        val decided = late.decide(2, FindingDisposition.CLEARED_FALSE_POSITIVE)
        assertThatThrownBy { decided.exitProposed(reviewerA, "done", true, t1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            decided.exitProposed(StaffIdentity(" ", "staff-z", strongAuth = true), evidence, true, t1)
        }
            .isInstanceOf(IllegalArgumentException::class.java)

        val proposed = decided.exitProposed(reviewerA, evidence, true, t1)
        assertThat(proposed.monitored).describedAs("a proposal ends nothing").isTrue()
        assertThat(proposed.monitoring.exitOwedSince).isNotNull()
        assertThat(proposed.monitoredSubjects).hasSize(2)

        // Must-refuse: the proposer confirming their own proposal; a confirmation of evidence
        // other than the proposal's (a proposal replaced after it was read).
        assertThatThrownBy { proposed.exitConfirmed(reviewerA, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("four-eyes")
        assertThatThrownBy { proposed.exitConfirmed(reviewerB, "something else entirely", true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)

        val exited = proposed.exitConfirmed(reviewerB, evidence, true, t1.plusSeconds(900))
        assertThat(exited.monitoring.exitOwedSince).isNull()
        assertThat(exited.monitoring.exitCompleted?.completedBy).isEqualTo("reviewer-b")
        assertThat(exited.monitoring.exitProposal?.proposedBy).isEqualTo("reviewer-a")
        assertThat(exited.monitored).isFalse()
        assertThat(exited.monitoredSubjects).isEmpty()
        // Nothing is monitored any more, so nothing more is recorded; an exit is completed once.
        assertThatThrownBy { exited.monitoringMatched(finding(3)) }.isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { exited.exitConfirmed(reviewerC, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        // Control: a case that owes no exit cannot propose one.
        assertThatThrownBy { case(CaseStatus.REJECTED).exitProposed(reviewerA, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
    }

    @Test
    fun `an exit cannot be recorded while the relationship is live in kyb or in party-service`() {
        // An ACTIVE case: refused even with the party closed and two reviewers.
        val active = case(CaseStatus.ACTIVE).monitoringMatched(finding(1)).decide(1, FindingDisposition.CONFIRMED)
        assertThatThrownBy { active.exitProposed(reviewerA, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("not demonstrably ended")
        // SIGNED is approved too.
        assertThatThrownBy { owedOn(CaseStatus.SIGNED).exitProposed(reviewerA, evidence, true, t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        // Rejected in kyb, but the party is still live.
        val rejected = owedOn(CaseStatus.REJECTED)
        assertThatThrownBy { rejected.exitProposed(reviewerA, evidence, entityPartyClosed = false, at = t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        // A proposal made while it had ended does not carry a confirmation past a re-opened party.
        val proposed = rejected.exitProposed(reviewerA, evidence, true, t1)
        assertThatThrownBy { proposed.exitConfirmed(reviewerB, evidence, entityPartyClosed = false, at = t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThat(proposed.monitored).isTrue()
        // Control: abandoned + closed is ended.
        val abandoned = owedOn(CaseStatus.ABANDONED).exitProposed(reviewerA, evidence, true, t1)
        assertThat(abandoned.exitConfirmed(reviewerB, evidence, true, t1).monitored).isFalse()
    }

    @Test
    fun `a monitoring hold keeps party activation pending, and only a clean resolution applies it`() {
        val signed = case(CaseStatus.SIGNED)
        // Control: without a finding, party activation activates.
        assertThat(signed.entityPartyActivated(t1).status).isEqualTo(CaseStatus.ACTIVE)

        val held = signed.monitoringMatched(finding(1)).monitoringMatched(finding(2)).entityPartyActivated(t1)
        assertThat(held.status).describedAs("an open finding holds the activation").isEqualTo(CaseStatus.SIGNED)
        assertThat(held.entityPartyActive).describedAs("the activation is recorded as pending").isTrue()
        assertThat(held.updatedAt).isEqualTo(t1)

        // One finding cleared, one still open: still held.
        val half = held.decide(1, FindingDisposition.CLEARED_FALSE_POSITIVE)
        assertThat(half.status).isEqualTo(CaseStatus.SIGNED)
        // The last one cleared: the pending activation is applied, and the case reads as if it
        // had never waited — the same updatedAt the activation set.
        val clean = half.decide(2, FindingDisposition.CLEARED_FALSE_POSITIVE)
        assertThat(clean.status).isEqualTo(CaseStatus.ACTIVE)
        assertThat(clean.updatedAt).isEqualTo(t1)

        // A confirmed sanctions match: never activated, not when decided, not after the exit.
        val confirmed = half.decide(2, FindingDisposition.CONFIRMED)
        assertThat(confirmed.status).isEqualTo(CaseStatus.SIGNED)
        assertThat(confirmed.entityPartyActivated(t1.plusSeconds(5)).status).isEqualTo(CaseStatus.SIGNED)
        val exited = confirmed.rejected("ended by the bank", t1)
            .exitProposed(reviewerA, evidence, true, t1)
            .exitConfirmed(reviewerB, evidence, true, t1)
        assertThat(exited.status).isEqualTo(CaseStatus.REJECTED)
        assertThat(exited.monitoring.blocksActivation).isTrue()

        // A confirmed PEP match is a risk fact, not a hold once decided: activation applies.
        val pep = signed.monitoringMatched(finding(3, kind = "PEP")).entityPartyActivated(t1)
        assertThat(pep.status).isEqualTo(CaseStatus.SIGNED)
        assertThat(pep.decide(3, FindingDisposition.CONFIRMED).status).isEqualTo(CaseStatus.ACTIVE)
    }
}
