// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.FindingDisposition
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.MonitoringMatch
import com.openbank.kyb.domain.model.ScreeningFindingException
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.Signer
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.kyb.domain.model.UboSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The aggregate's side of ongoing monitoring: who is to be registered, what a finding does to an
 * approved case, and how an operator gets the case back out of the review.
 */
class CaseMonitoringTest {

    private val t0: Instant = Instant.parse("2026-11-13T09:00:00Z")
    private val t1: Instant = t0.plusSeconds(3600)
    private val entityParty: UUID = UUID.fromString("0190a000-0000-7000-8000-000000000001")
    private val personRef = "5b0c2d3e-0000-4000-8000-0000000000aa"

    private fun subject(kind: ScreeningSubjectKind, name: String, ref: String?, current: Boolean = true) =
        ScreeningSubject(
            key = "k-$name",
            kind = kind,
            name = name,
            organisation = kind == ScreeningSubjectKind.ENTITY,
            sanctions = ScreeningVerdict.CLEAR,
            ref = ref,
            current = current,
        )

    private fun screening() = CaseScreening(
        listOf(
            subject(ScreeningSubjectKind.ENTITY, "Example Works s.r.o.", entityParty.toString()),
            subject(ScreeningSubjectKind.REPRESENTATIVE, "Quentin Example", personRef),
        ),
        UboSource.REGISTER,
    )

    private fun case(status: CaseStatus, screening: CaseScreening? = screening(), active: Boolean = false) =
        BusinessOnboardingCase(
            id = UUID.fromString("0190a000-0000-7000-8000-0000000000c1"),
            identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
            initiatorPartyId = UUID.fromString("0190a000-0000-7000-8000-0000000000b1"),
            status = status,
            extract = null,
            entityPartyId = entityParty,
            requiredSignatures = 1,
            signers = listOf(
                Signer(
                    id = UUID.fromString("0190a000-0000-7000-8000-0000000000d1"),
                    representativeIndex = 0,
                    fullName = "Quentin Example",
                    dateOfBirth = null,
                    partyId = UUID.fromString("0190a000-0000-7000-8000-0000000000b1"),
                    status = SignerStatus.SIGNED,
                    invitationToken = null,
                    invitedAt = null,
                    identifiedAt = t0,
                    signedAt = t0,
                    signatureRef = "ceremony",
                    isInitiator = true,
                ),
            ),
            reviewReason = null,
            entityPartyActive = active,
            screening = screening,
            createdAt = t0,
            updatedAt = t0,
        )

    private fun match(
        id: String = "0190a000-0000-7000-8000-0000000000f1",
        kind: String = "SANCTIONS",
        status: String = "POTENTIAL_HIT",
    ) = MonitoringMatch(
        findingId = UUID.fromString(id),
        matchKind = kind,
        listType = "EU_CONSOLIDATED",
        listEntryRef = "id:synthetic-1",
        listEntryVersion = "v1",
        matchScore = 0.91,
        matchStatus = status,
        detection = "LIST_DELTA",
        receivedAt = t1,
    )

    @Test
    fun `only an approved case has subjects to monitor, and only its current, referenced ones`() {
        val withLeaver = screening().copy(
            subjects = screening().subjects +
                subject(ScreeningSubjectKind.BENEFICIAL_OWNER, "Former Owner", "ref-left", current = false) +
                subject(ScreeningSubjectKind.DECLARED_PERSON, "No Reference Yet", null),
        )
        assertThat(case(CaseStatus.SIGNED, withLeaver).monitoredSubjects.map { it.ref })
            .containsExactly(entityParty.toString(), personRef)
        assertThat(case(CaseStatus.ACTIVE).monitoredSubjects).hasSize(2)
        // Every status before approval and after the end of the case: nobody.
        listOf(
            CaseStatus.READY_TO_SIGN,
            CaseStatus.AWAITING_COSIGNERS,
            CaseStatus.MANUAL_REVIEW,
            CaseStatus.REJECTED,
            CaseStatus.ABANDONED,
        ).forEach { assertThat(case(it).monitoredSubjects).describedAs(it.name).isEmpty() }
        assertThat(case(CaseStatus.ACTIVE, screening = null).monitoredSubjects).isEmpty()
    }

    @Test
    fun `references are opaque, the entity takes its party id, and a reference is never replaced`() {
        val bare = CaseScreening(
            screening().subjects.map { it.copy(ref = null) } +
                subject(ScreeningSubjectKind.BENEFICIAL_OWNER, "Former Owner", null, current = false),
            UboSource.REGISTER,
        )
        val minted = mutableListOf<String>()
        val mint = { UUID.randomUUID().toString().also { minted += it } }
        val withRefs = case(CaseStatus.READY_TO_SIGN, bare).withMonitoringRefs(mint, t1)

        val subjects = requireNotNull(withRefs.screening).subjects
        assertThat(subjects[0].ref).isEqualTo(entityParty.toString())
        assertThat(subjects[1].ref).isEqualTo(minted.single())
        // Nothing of the person is in the reference: not the name, not the name-derived key.
        assertThat(subjects[1].ref).doesNotContain("Quentin", "Example", subjects[1].key)
        assertThat(subjects[2].ref).describedAs("a subject that left the case is not given one").isNull()

        val again = withRefs.withMonitoringRefs({ error("nothing left to mint") }, t1.plusSeconds(1))
        assertThat(again).isSameAs(withRefs)
    }

    @Test
    fun `a finding sends an approved case to review, records the entry, and leaves the applicant's view as it was`() {
        val active = case(CaseStatus.ACTIVE, active = true)

        val reviewed = active.monitoringMatched(personRef, match(), t1)

        assertThat(reviewed.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(reviewed.monitoringReview?.from).isEqualTo(CaseStatus.ACTIVE)
        assertThat(reviewed.monitoringReview?.openedAt).isEqualTo(t1)
        val person = requireNotNull(reviewed.screening).subjects[1]
        assertThat(person.sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
        assertThat(person.disposition).isEqualTo(FindingDisposition.OPEN)
        assertThat(person.monitoringFindings.single().listEntryRef).isEqualTo("id:synthetic-1")
        assertThat(requireNotNull(reviewed.screening).subjects[0].monitoringFindings).isEmpty()
        // Still monitored while under review: nothing is withdrawn because a finding arrived.
        assertThat(reviewed.monitoredSubjects).hasSize(2)
        // The applicant sees an active case and no reason; the operator sees the review.
        assertThat(reviewed.applicantStatus).isEqualTo(CaseStatus.ACTIVE)
        assertThat(reviewed.applicantReviewReason).isNull()
        assertThat(reviewed.reviewReason).isEqualTo(BusinessOnboardingCase.MONITORING_REVIEW)
    }

    @Test
    fun `the same finding id twice writes nothing, a second finding keeps the first review`() {
        val reviewed = case(CaseStatus.SIGNED).monitoringMatched(personRef, match(), t1)

        assertThat(reviewed.monitoringMatched(personRef, match(), t1.plusSeconds(60))).isSameAs(reviewed)

        val second = reviewed.monitoringMatched(
            entityParty.toString(),
            match(id = "0190a000-0000-7000-8000-0000000000f2", kind = "PEP"),
            t1.plusSeconds(120),
        )
        assertThat(second.monitoringReview).isEqualTo(reviewed.monitoringReview)
        val entity = requireNotNull(second.screening).subjects[0]
        // A PEP match is a PEP fact, never a sanctions verdict.
        assertThat(entity.pep).isTrue()
        assertThat(entity.sanctions).isEqualTo(ScreeningVerdict.CLEAR)
        assertThat(requireNotNull(second.screening).openFindings).hasSize(2)
    }

    @Test
    fun `a kind this service does not know still opens a review, as a sanctions match`() {
        val reviewed = case(CaseStatus.ACTIVE, active = true)
            .monitoringMatched(personRef, match(kind = "SOMETHING_NEW", status = "SOMETHING_ELSE"), t1)

        val person = requireNotNull(reviewed.screening).subjects[1]
        assertThat(person.sanctionsHit).isTrue()
        assertThat(person.findingOpen).isTrue()
        assertThat(reviewed.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
    }

    @Test
    fun `a finding reopens a subject an operator had already decided, and keeps that decision as history`() {
        val decided = screening().copy(
            subjects = screening().subjects.map {
                if (it.ref != personRef) {
                    it
                } else {
                    it.copy(
                        pep = true,
                        disposition = FindingDisposition.CONFIRMED,
                        disposedBy = "operator-1",
                        dispositionReason = "a member of a municipal council",
                        disposedAt = t0,
                    )
                }
            },
        )
        val reviewed = case(CaseStatus.ACTIVE, decided, active = true).monitoringMatched(personRef, match(), t1)

        val person = requireNotNull(reviewed.screening).subjects[1]
        assertThat(person.disposition).isEqualTo(FindingDisposition.OPEN)
        assertThat(person.disposedBy).isNull()
        assertThat(person.pastDispositions.single().disposition).isEqualTo(FindingDisposition.CONFIRMED)
        assertThat(person.pastDispositions.single().disposedBy).isEqualTo("operator-1")
    }

    @Test
    fun `a finding is refused for a case that is not monitored and for a reference nobody carries`() {
        assertThatThrownBy { case(CaseStatus.READY_TO_SIGN).monitoringMatched(personRef, match(), t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { case(CaseStatus.REJECTED).monitoringMatched(personRef, match(), t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { case(CaseStatus.ACTIVE).monitoringMatched("someone-else", match(), t1) }
            .isInstanceOf(CaseTransitionException::class.java)
    }

    @Test
    fun `the review is resolved only once the finding is decided, and returns the case to where it stood`() {
        val reviewed = case(CaseStatus.ACTIVE, active = true).monitoringMatched(personRef, match(), t1)

        // Must-refuse: the finding is open, whatever the screening switch says.
        assertThatThrownBy { reviewed.reviewResolved(1, t1, screeningRequired = false) }
            .isInstanceOf(ScreeningFindingException::class.java)
            .extracting("code").isEqualTo(ScreeningFindingException.OPEN)

        val cleared = reviewed.findingDisposed(
            "k-Quentin Example",
            FindingDisposition.CLEARED_FALSE_POSITIVE,
            "operator-2",
            "different year of birth",
            t1,
        )
        val resolved = cleared.reviewResolved(1, t1.plusSeconds(1), screeningRequired = true)

        assertThat(resolved.status).isEqualTo(CaseStatus.ACTIVE)
        assertThat(resolved.monitoringReview).isNull()
        assertThat(resolved.reviewReason).isNull()
        assertThat(resolved.monitoredSubjects).hasSize(2)
    }

    @Test
    fun `a confirmed sanctions finding leaves only rejection, and a rejected case monitors nobody`() {
        val reviewed = case(CaseStatus.ACTIVE, active = true).monitoringMatched(personRef, match(), t1)
        val confirmed = reviewed.findingDisposed(
            "k-Quentin Example",
            FindingDisposition.CONFIRMED,
            "operator-2",
            "same person as the listed entry",
            t1,
        )

        assertThatThrownBy { confirmed.reviewResolved(1, t1, screeningRequired = true) }
            .isInstanceOf(ScreeningFindingException::class.java)
            .extracting("code").isEqualTo(ScreeningFindingException.CONFIRMED)

        val rejected = confirmed.rejected("confirmed sanctions match", t1.plusSeconds(5))
        assertThat(rejected.status).isEqualTo(CaseStatus.REJECTED)
        assertThat(rejected.monitoredSubjects).isEmpty()
        assertThat(rejected.applicantStatus).isEqualTo(CaseStatus.REJECTED)
    }

    @Test
    fun `a review opened before activation completes the case when it is resolved after it`() {
        val reviewed = case(CaseStatus.SIGNED).monitoringMatched(personRef, match(kind = "PEP"), t1)
        assertThat(reviewed.applicantStatus).isEqualTo(CaseStatus.SIGNED)

        val activated = reviewed.entityPartyActivated(t1.plusSeconds(10))
        assertThat(activated.status).describedAs("activation does not end a review").isEqualTo(CaseStatus.MANUAL_REVIEW)

        val resolved = activated
            .findingDisposed("k-Quentin Example", FindingDisposition.CONFIRMED, "operator-2", "a PEP, accepted", t1)
            .reviewResolved(1, t1.plusSeconds(20), screeningRequired = true)
        assertThat(resolved.status).isEqualTo(CaseStatus.ACTIVE)
    }

    @Test
    fun `nobody but an operator ends a case that is under a monitoring review`() {
        val reviewed = case(CaseStatus.SIGNED).monitoringMatched(personRef, match(), t1)

        assertThatThrownBy { reviewed.abandoned(t1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            // The applicant is told what they would be told about a case that is simply signed.
            .hasMessage("case is already SIGNED")
        // Control: without a monitoring review a signed case can still be abandoned.
        assertThat(case(CaseStatus.SIGNED).abandoned(t1).status).isEqualTo(CaseStatus.ABANDONED)
    }
}
