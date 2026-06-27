// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.entity

import com.openbank.pid.domain.model.ApplicantSnapshot
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import com.openbank.pid.domain.model.VerificationTrigger
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Panache entity for `identity_verification_case` (V6). Maps the four-eyes case aggregate.
 * Candidate party ids and nationalities are stored comma-separated; enums as their name strings.
 */
@Entity
@Table(name = "identity_verification_case")
class VerificationCaseEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "dedup_key", nullable = false)
    lateinit var dedupKey: String

    @Column(name = "trigger", nullable = false)
    lateinit var trigger: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "applicant_given_name", nullable = false)
    lateinit var applicantGivenName: String

    @Column(name = "applicant_family_name", nullable = false)
    lateinit var applicantFamilyName: String

    @Column(name = "applicant_birthdate", nullable = false)
    lateinit var applicantBirthdate: LocalDate

    @Column(name = "applicant_birthplace")
    var applicantBirthplace: String? = null

    @Column(name = "applicant_nationalities", nullable = false)
    var applicantNationalities: String = ""

    @Column(name = "blind_index")
    var blindIndex: String? = null

    @Column(name = "candidate_party_ids", nullable = false)
    var candidatePartyIds: String = ""

    @Column(name = "first_approver")
    var firstApprover: String? = null

    @Column(name = "first_verdict")
    var firstVerdict: String? = null

    @Column(name = "first_link_party_id")
    var firstLinkPartyId: UUID? = null

    @Column(name = "first_notes")
    var firstNotes: String? = null

    @Column(name = "first_at")
    var firstAt: Instant? = null

    @Column(name = "second_approver")
    var secondApprover: String? = null

    @Column(name = "second_at")
    var secondAt: Instant? = null

    @Column(name = "final_verdict")
    var finalVerdict: String? = null

    @Column(name = "final_link_party_id")
    var finalLinkPartyId: UUID? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    fun toDomain(): VerificationCase = VerificationCase(
        id = id,
        dedupKey = dedupKey,
        trigger = VerificationTrigger.valueOf(trigger),
        status = VerificationCaseStatus.valueOf(status),
        applicant = ApplicantSnapshot(
            givenName = applicantGivenName,
            familyName = applicantFamilyName,
            birthdate = applicantBirthdate,
            birthplace = applicantBirthplace,
            nationalities = applicantNationalities.splitCsv(),
        ),
        blindIndex = blindIndex,
        candidatePartyIds = candidatePartyIds.splitCsv().map(UUID::fromString),
        firstApprover = firstApprover,
        firstVerdict = firstVerdict?.let(CaseVerdict::valueOf),
        firstLinkPartyId = firstLinkPartyId,
        firstNotes = firstNotes,
        firstAt = firstAt,
        secondApprover = secondApprover,
        secondAt = secondAt,
        finalVerdict = finalVerdict?.let(CaseVerdict::valueOf),
        finalLinkPartyId = finalLinkPartyId,
        decidedAt = decidedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(case: VerificationCase): VerificationCaseEntity = VerificationCaseEntity().apply {
            id = case.id
            dedupKey = case.dedupKey
            trigger = case.trigger.name
            status = case.status.name
            applicantGivenName = case.applicant.givenName
            applicantFamilyName = case.applicant.familyName
            applicantBirthdate = case.applicant.birthdate
            applicantBirthplace = case.applicant.birthplace
            applicantNationalities = case.applicant.nationalities.joinToString(",")
            blindIndex = case.blindIndex
            candidatePartyIds = case.candidatePartyIds.joinToString(",")
            firstApprover = case.firstApprover
            firstVerdict = case.firstVerdict?.name
            firstLinkPartyId = case.firstLinkPartyId
            firstNotes = case.firstNotes
            firstAt = case.firstAt
            secondApprover = case.secondApprover
            secondAt = case.secondAt
            finalVerdict = case.finalVerdict?.name
            finalLinkPartyId = case.finalLinkPartyId
            decidedAt = case.decidedAt
            createdAt = case.createdAt
            updatedAt = case.updatedAt
        }

        private fun String.splitCsv(): List<String> = split(",").map(String::trim).filter(String::isNotEmpty)
    }
}
