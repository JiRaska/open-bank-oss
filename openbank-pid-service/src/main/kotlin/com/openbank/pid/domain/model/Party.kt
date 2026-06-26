// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.domain.model

import com.openbank.libs.domain.case.CaseId
import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseStatus
import com.openbank.libs.domain.case.CaseType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class Party(
    val id: UUID,
    val partyType: PartyType,
    val status: PartyStatus,
    val externalIds: List<ExternalId>,
    val coreAttributes: CoreAttributes,
    val addressAttributes: AddressAttributes?,
    val contactAttributes: ContactAttributes,
    val kycAttributes: KycAttributes,
    val relationships: List<PartyRelationship>,
    val caseLifecycle: PartyCaseLifecycle? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val version: Long,
) {
    fun hasRole(role: PartyRole): Boolean =
        relationships.any { it.role == role && it.status == RelationshipStatus.ACTIVE }

    fun isCustomer(): Boolean = hasRole(PartyRole.CUSTOMER)
    fun isEmployee(): Boolean = hasRole(PartyRole.EMPLOYEE)

    fun activeRelationships(): List<PartyRelationship> = relationships.filter { it.status == RelationshipStatus.ACTIVE }

    fun externalId(type: ExternalIdType): String? = externalIds.firstOrNull { it.type == type }?.value
}

data class PartyCaseLifecycle(
    val caseId: CaseId,
    val caseType: CaseType,
    val status: CaseStatus,
    val lastActor: String,
    val lastReasonCode: CaseReasonCode,
    val lastTransitionAt: OffsetDateTime,
    val metadata: Map<String, String> = emptyMap(),
)

enum class PartyType {
    NATURAL_PERSON,
    LEGAL_ENTITY,
    SOLE_TRADER,
}

enum class PartyStatus {
    ACTIVE,
    SUSPENDED,
    DECEASED,
    TERMINATED,
}

data class ExternalId(val type: ExternalIdType, val value: String, val verifiedAt: OffsetDateTime? = null)

enum class ExternalIdType {
    KEYCLOAK_ID,
    BANKID_SUB,
    ROB_AIFO,
    ICO,
    PASSPORT_NUMBER,
    ID_CARD_NUMBER,

    /** Keyed blind index (HMAC-SHA256 hex) of the canonical Czech RČ. Never the plaintext. */
    BIRTH_NUMBER,

    /**
     * Keyed blind index (HMAC-SHA256 hex) of the verified EUDI PID subject identifier (ADR-0094).
     * The tier-0 deterministic dedup key; set only after cryptographic verification of the wallet
     * presentation. Never the plaintext government identifier.
     */
    EUDI_PID_SUB,
}

data class CoreAttributes(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthNumberEncrypted: String?,
    val gender: Gender?,
    val birthplace: String?,
    val nationalities: List<String>,
    val idDocuments: List<IdDocument>,
    val verificationSource: VerificationSource,
    val verifiedAt: OffsetDateTime,
)

enum class Gender { MALE, FEMALE, OTHER }

enum class VerificationSource {
    BANKID,
    BRANCH_MANUAL,
    API_UPLOAD,
    ROB,
}

data class IdDocument(
    val type: IdDocumentType,
    val number: String,
    val issuingCountry: String,
    val issuedAt: LocalDate?,
    val expiresAt: LocalDate?,
)

enum class IdDocumentType {
    NATIONAL_ID,
    PASSPORT,
    DRIVING_LICENSE,
    RESIDENCE_PERMIT,
}

data class AddressAttributes(
    val permanentAddress: Address?,
    val mailingAddress: Address?,
    val robSyncedAt: OffsetDateTime?,
    val robSyncSource: String? = "ISZR",
)

data class Address(
    val street: String?,
    val houseNumber: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
    val ruianCode: String? = null,
    val effectiveFrom: LocalDate? = null,
    val effectiveTo: LocalDate? = null,
)

data class ContactAttributes(
    val email: String?,
    val emailVerifiedAt: OffsetDateTime?,
    val phone: String?,
    val phoneVerifiedAt: OffsetDateTime?,
    val preferredLanguage: String = "cs",
    val dataBoxId: String? = null,
)

data class KycAttributes(
    val kycLevel: KycLevel,
    val kycCompletedAt: OffsetDateTime?,
    val kycExpiresAt: OffsetDateTime?,
    val amlRiskScore: AmlRiskScore,
    val pepFlag: Boolean,
    val sanctionsFlag: Boolean,
    val uboVerifiedAt: OffsetDateTime?,
    val lastAmlReviewAt: OffsetDateTime?,
)

enum class KycLevel {
    NONE,
    BASIC,
    ENHANCED,
    FULL,
}

enum class AmlRiskScore {
    LOW,
    MEDIUM,
    HIGH,
    UNACCEPTABLE,
}

data class PartyRelationship(
    val id: UUID,
    val partyId: UUID,
    val role: PartyRole,
    val status: RelationshipStatus,
    val onboardedAt: OffsetDateTime,
    val onboardingChannel: OnboardingChannel,
    val terminatedAt: OffsetDateTime?,
    val terminationReason: String?,
)

enum class PartyRole {
    CUSTOMER,
    EMPLOYEE,
    ADMIN,
    AGENT,
    GUARANTOR,
    AUTHORIZED_PERSON,
}

enum class RelationshipStatus {
    ACTIVE,
    SUSPENDED,
    TERMINATED,
}

enum class OnboardingChannel {
    BANKID,
    BRANCH,
    API,
    MOBILE_APP,
}
