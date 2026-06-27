// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "parties")
class PartyEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_type", nullable = false, length = 30)
    lateinit var partyType: String

    @Column(name = "status", nullable = false, length = 20)
    lateinit var status: String

    @Column(name = "given_name", nullable = false, length = 100)
    lateinit var givenName: String

    @Column(name = "family_name", nullable = false, length = 100)
    lateinit var familyName: String

    @Column(name = "birthdate", nullable = false)
    lateinit var birthdate: LocalDate

    @Column(name = "birth_number_encrypted", length = 512)
    var birthNumberEncrypted: String? = null

    @Column(name = "gender", length = 10)
    var gender: String? = null

    @Column(name = "birthplace", length = 200)
    var birthplace: String? = null

    @Column(name = "nationalities", nullable = false, columnDefinition = "text[]")
    lateinit var nationalities: Array<String>

    @Column(name = "verification_source", nullable = false, length = 30)
    lateinit var verificationSource: String

    @Column(name = "verified_at", nullable = false)
    lateinit var verifiedAt: OffsetDateTime

    @Column(name = "email", length = 255)
    var email: String? = null

    @Column(name = "email_verified_at")
    var emailVerifiedAt: OffsetDateTime? = null

    @Column(name = "phone", length = 30)
    var phone: String? = null

    @Column(name = "phone_verified_at")
    var phoneVerifiedAt: OffsetDateTime? = null

    @Column(name = "preferred_language", nullable = false, length = 5)
    var preferredLanguage: String = "cs"

    @Column(name = "data_box_id", length = 20)
    var dataBoxId: String? = null

    @Column(name = "kyc_level", nullable = false, length = 20)
    lateinit var kycLevel: String

    @Column(name = "kyc_completed_at")
    var kycCompletedAt: OffsetDateTime? = null

    @Column(name = "kyc_expires_at")
    var kycExpiresAt: OffsetDateTime? = null

    @Column(name = "aml_risk_score", nullable = false, length = 20)
    lateinit var amlRiskScore: String

    @Column(name = "pep_flag", nullable = false)
    var pepFlag: Boolean = false

    @Column(name = "sanctions_flag", nullable = false)
    var sanctionsFlag: Boolean = false

    @Column(name = "ubo_verified_at")
    var uboVerifiedAt: OffsetDateTime? = null

    @Column(name = "last_aml_review_at")
    var lastAmlReviewAt: OffsetDateTime? = null

    @Column(name = "permanent_address_street", length = 200)
    var permanentAddressStreet: String? = null

    @Column(name = "permanent_address_house_number", length = 20)
    var permanentAddressHouseNumber: String? = null

    @Column(name = "permanent_address_city", length = 100)
    var permanentAddressCity: String? = null

    @Column(name = "permanent_address_postal_code", length = 10)
    var permanentAddressPostalCode: String? = null

    @Column(name = "permanent_address_country", length = 3)
    var permanentAddressCountry: String? = null

    @Column(name = "permanent_address_ruian_code", length = 20)
    var permanentAddressRuianCode: String? = null

    @Column(name = "rob_synced_at")
    var robSyncedAt: OffsetDateTime? = null

    @Column(name = "case_id")
    var caseId: UUID? = null

    @Column(name = "case_type", length = 50)
    var caseType: String? = null

    @Column(name = "case_status", length = 40)
    var caseStatus: String? = null

    @Column(name = "case_last_actor", length = 100)
    var caseLastActor: String? = null

    @Column(name = "case_last_reason_code", length = 40)
    var caseLastReasonCode: String? = null

    @Column(name = "case_last_transition_at")
    var caseLastTransitionAt: OffsetDateTime? = null

    @Column(name = "case_metadata", columnDefinition = "TEXT")
    var caseMetadata: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}

@Entity
@Table(name = "party_external_ids")
class PartyExternalIdEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "id_type", nullable = false, length = 30)
    lateinit var idType: String

    @Column(name = "id_value", nullable = false, length = 255)
    lateinit var idValue: String

    @Column(name = "verified_at")
    var verifiedAt: OffsetDateTime? = null
}

@Entity
@Table(name = "party_id_documents")
class PartyIdDocumentEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "doc_type", nullable = false, length = 30)
    lateinit var docType: String

    @Column(name = "doc_number", nullable = false, length = 50)
    lateinit var docNumber: String

    @Column(name = "issuing_country", nullable = false, length = 3)
    lateinit var issuingCountry: String

    @Column(name = "issued_at")
    var issuedAt: LocalDate? = null

    @Column(name = "expires_at")
    var expiresAt: LocalDate? = null
}

@Entity
@Table(name = "party_relationships")
class PartyRelationshipEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "role", nullable = false, length = 30)
    lateinit var role: String

    @Column(name = "status", nullable = false, length = 20)
    lateinit var status: String

    @Column(name = "onboarded_at", nullable = false)
    lateinit var onboardedAt: OffsetDateTime

    @Column(name = "onboarding_channel", nullable = false, length = 20)
    lateinit var onboardingChannel: String

    @Column(name = "terminated_at")
    var terminatedAt: OffsetDateTime? = null

    @Column(name = "termination_reason", length = 500)
    var terminationReason: String? = null
}
