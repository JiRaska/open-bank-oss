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
        // The registration store is touched only for a case that is or was approved.
        assertThat(CaseStatus.entries.filter { case(it).everApproved }).containsExactlyInAnyOrder(
            CaseStatus.SIGNED,
            CaseStatus.ACTIVE,
            CaseStatus.REJECTED,
            CaseStatus.ABANDONED,
        )
    }

    @Test
    fun `a person not yet screened gets a random reference, the entity its party id, and neither is replaced`() {
        val bare = CaseScreening(
            screening().subjects.map { it.copy(ref = null, sanctions = ScreeningVerdict.PENDING) } +
                subject(ScreeningSubjectKind.BENEFICIAL_OWNER, "Former Owner", null, current = false),
            UboSource.REGISTER,
        )
        val minted = mutableListOf<String>()
        val mint = { UUID.randomUUID().toString().also { minted += it } }
        val withRefs = case(CaseStatus.READY_TO_SIGN, bare).withMonitoringRefs(mint)

        val subjects = requireNotNull(withRefs.screening).subjects
        assertThat(subjects[0].ref).isEqualTo(entityParty.toString())
        assertThat(subjects[1].ref).isEqualTo(minted.single())
        // Nothing of the person is in the reference: not the name, not the name-derived key.
        assertThat(subjects[1].ref).doesNotContain("Quentin", "Example", subjects[1].key)
        assertThat(subjects[2].ref).describedAs("a subject that left the case is not given one").isNull()
        assertThat(withRefs.updatedAt).describedAs("a reference is not a change the applicant sees").isEqualTo(t0)

        assertThat(withRefs.withMonitoringRefs { error("nothing left to mint") }).isSameAs(withRefs)
    }

    @Test
    fun `an entity without a party has no reference, so it cannot be screened or registered`() {
        val bare = CaseScreening(screening().subjects.map { it.copy(ref = null) }, UboSource.REGISTER)

        val noParty = case(CaseStatus.MANUAL_REVIEW, bare).copy(entityPartyId = null).withMonitoringRefs { "minted" }

        assertThat(requireNotNull(noParty.screening).subjects[0].ref).isNull()
        assertThat(requireNotNull(noParty.screening).monitorable.map { it.kind })
            .doesNotContain(ScreeningSubjectKind.ENTITY)
        // Once the party exists the reference is the party id, and only then.
        val withParty = noParty.copy(entityPartyId = entityParty).withMonitoringRefs { "minted" }
        assertThat(requireNotNull(withParty.screening).subjects[0].ref).isEqualTo(entityParty.toString())
    }

    @Test
    fun `a person screened by an earlier release keeps the id that screen used`() {
        // Answered, and no reference: what a release without references left behind.
        val legacy = CaseScreening(screening().subjects.map { it.copy(ref = null) }, UboSource.REGISTER)

        val withRefs = case(CaseStatus.ACTIVE, legacy).withMonitoringRefs {
            error("an answered subject is never re-identified")
        }

        val subjects = requireNotNull(withRefs.screening).subjects
        // Exactly the two forms the old screen sent as subjectId, so its check and any clearance still bind.
        assertThat(subjects[0].ref).isEqualTo(entityParty.toString())
        assertThat(subjects[1].ref).isEqualTo("kyb-case:${withRefs.id}:k-Quentin Example")
    }

    @Test
    fun `a finding opens a review beside the status, and changes nothing an applicant can read`() {
        val active = case(CaseStatus.ACTIVE, active = true)

        val reviewed = active.monitoringMatched(personRef, match(), t1)

        // The whole point: status, reason and updatedAt are exactly what they were.
        assertThat(reviewed.status).isEqualTo(CaseStatus.ACTIVE)
        assertThat(reviewed.reviewReason).isNull()
        assertThat(reviewed.applicantReviewReason).isNull()
        assertThat(reviewed.updatedAt).isEqualTo(active.updatedAt)
        assertThat(reviewed.copy(screening = active.screening, monitoringReview = null)).isEqualTo(active)

        assertThat(reviewed.monitoringReview?.openedAt).isEqualTo(t1)
        assertThat(reviewed.monitoringReview?.open).isTrue()
        val person = requireNotNull(reviewed.screening).subjects[1]
        assertThat(person.sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
        assertThat(person.disposition).isEqualTo(FindingDisposition.OPEN)
        assertThat(person.monitoringFindings.single().listEntryRef).isEqualTo("id:synthetic-1")
        assertThat(requireNotNull(reviewed.screening).subjects[0].monitoringFindings).isEmpty()
        assertThat(reviewed.monitoredSubjects).hasSize(2)
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
        assertThat(reviewed.monitoringReview?.open).isTrue()
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
    fun `the review closes only once the finding is decided, and the case is what it was all along`() {
        val active = case(CaseStatus.ACTIVE, active = true)
        val reviewed = active.monitoringMatched(personRef, match(), t1)

        // Must-refuse: the finding is open.
        assertThatThrownBy { reviewed.monitoringReviewResolved() }
            .isInstanceOf(ScreeningFindingException::class.java)
            .extracting("code").isEqualTo(ScreeningFindingException.OPEN)

        val cleared = reviewed.findingDisposed(
            "k-Quentin Example",
            FindingDisposition.CLEARED_FALSE_POSITIVE,
            "operator-2",
            "different year of birth",
            t1,
        )
        assertThat(cleared.updatedAt).describedAs("the operator's decision is not the applicant's to see").isEqualTo(t0)
        val resolved = cleared.monitoringReviewResolved()

        assertThat(resolved.status).isEqualTo(CaseStatus.ACTIVE)
        assertThat(resolved.monitoringReview).isNull()
        assertThat(resolved.updatedAt).isEqualTo(t0)
        assertThat(resolved.monitoredSubjects).hasSize(2)
        // Control: without a review there is nothing to resolve, and a finding cannot be decided on an approved case.
        assertThatThrownBy { active.monitoringReviewResolved() }.isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy {
            active.findingDisposed(
                "k-Quentin Example",
                FindingDisposition.CONFIRMED,
                "operator-2",
                "long enough reason",
                t1,
            )
        }.isInstanceOf(CaseTransitionException::class.java)
    }

    @Test
    fun `a confirmed sanctions finding leaves only an exit, and the customer stays approved and monitored`() {
        val reviewed = case(CaseStatus.ACTIVE, active = true).monitoringMatched(personRef, match(), t1)
        val confirmed = reviewed.findingDisposed(
            "k-Quentin Example",
            FindingDisposition.CONFIRMED,
            "operator-2",
            "same person as the listed entry",
            t1,
        )

        assertThatThrownBy { confirmed.monitoringReviewResolved() }
            .isInstanceOf(ScreeningFindingException::class.java)
            .extracting("code").isEqualTo(ScreeningFindingException.CONFIRMED)
        // An approved customer cannot be "rejected": that transition would end monitoring.
        assertThatThrownBy { confirmed.rejected("confirmed sanctions match", t1) }
            .isInstanceOf(CaseTransitionException::class.java)

        val exiting = confirmed.exitRequired("confirmed sanctions match", t1.plusSeconds(5))

        assertThat(exiting.status).isEqualTo(CaseStatus.ACTIVE)
        assertThat(exiting.monitored).isTrue()
        assertThat(exiting.monitoredSubjects).describedAs("nobody is withdrawn by a confirmed finding").hasSize(2)
        assertThat(exiting.monitoringReview?.exitRequiredAt).isEqualTo(t1.plusSeconds(5))
        assertThat(exiting.monitoringReview?.exitReason).isEqualTo("confirmed sanctions match")
        assertThat(exiting.updatedAt).isEqualTo(t0)
        // The decision is made once: it cannot be resolved away, repeated, or turned into a rejection.
        assertThatThrownBy { exiting.monitoringReviewResolved() }.isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { exiting.exitRequired("again", t1) }.isInstanceOf(CaseTransitionException::class.java)
        assertThatThrownBy { exiting.rejected("again", t1) }.isInstanceOf(CaseTransitionException::class.java)
        // A later finding is still recorded, and the exit stands.
        val later = exiting.monitoringMatched(
            entityParty.toString(),
            match(id = "0190a000-0000-7000-8000-0000000000f3"),
            t1.plusSeconds(60),
        )
        assertThat(later.monitoringReview).isEqualTo(exiting.monitoringReview)
        assertThat(requireNotNull(later.screening).subjects[0].monitoringFindings).hasSize(1)
    }

    @Test
    fun `an exit needs an open monitoring review and a reason`() {
        assertThatThrownBy { case(CaseStatus.ACTIVE).exitRequired("no review", t1) }
            .isInstanceOf(CaseTransitionException::class.java)
        val reviewed = case(CaseStatus.ACTIVE).monitoringMatched(personRef, match(), t1)
        assertThatThrownBy { reviewed.exitRequired("  ", t1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an outage of the ownership register never removes a subject, and a leaver keeps its reference`() {
        val owner = subject(ScreeningSubjectKind.BENEFICIAL_OWNER, "Olga Owner", "ref-owner")
        val before = screening().copy(subjects = screening().subjects + owner)
        val withoutOwner = before.subjects.take(2).map { it.copy(ref = null) }

        // The lookup could not be made: the owner's absence from the fresh list proves nothing.
        val duringOutage = before.rebuilt(withoutOwner, UboSource.UNAVAILABLE)
        assertThat(duringOutage.subjects.single { it.name == "Olga Owner" }.current).isTrue()
        assertThat(duringOutage.monitorable.map { it.ref }).contains("ref-owner")
        assertThat(duringOutage.complete).describedAs("and nothing is approved on such a list").isFalse()

        // The lookup answered without her: she left. She is kept, not current, with her reference.
        val left = before.rebuilt(withoutOwner, UboSource.REGISTER)
        val gone = left.subjects.single { it.name == "Olga Owner" }
        assertThat(gone.current).isFalse()
        assertThat(gone.ref).isEqualTo("ref-owner")
        assertThat(left.monitorable.map { it.ref }).doesNotContain("ref-owner")
        assertThat(left.complete).isTrue()

        // She comes back: the same subject, the same reference, the answer already obtained.
        val back = left.rebuilt(
            before.subjects.map {
                it.copy(ref = null, sanctions = ScreeningVerdict.PENDING)
            },
            UboSource.REGISTER,
        )
        val again = back.subjects.single { it.name == "Olga Owner" }
        assertThat(again.current).isTrue()
        assertThat(again.ref).isEqualTo("ref-owner")
        assertThat(again.sanctions).isEqualTo(ScreeningVerdict.CLEAR)
    }
}
