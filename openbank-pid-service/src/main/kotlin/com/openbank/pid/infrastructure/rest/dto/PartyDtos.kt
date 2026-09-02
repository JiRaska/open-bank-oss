// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest.dto

import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseStatus
import com.openbank.libs.domain.case.CaseType
import com.openbank.pid.domain.model.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CreatePartyRequest(
    val partyType: PartyType = PartyType.NATURAL_PERSON,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val nationalities: List<String> = listOf("CZ"),
    val verificationSource: VerificationSource = VerificationSource.BANKID,
    val bankIdSub: String? = null,
    /**
     * Plaintext Czech RČ.  pid-service immediately reduces it to a blind index and stores
     * the index in `party_external_ids(BIRTH_NUMBER)`.  The plaintext is never stored or
     * logged.  Omit for legal entities and foreign nationals without an RČ (ADR-0072).
     */
    val birthNumberRaw: String? = null,
    val initialRole: PartyRole = PartyRole.CUSTOMER,
    val onboardingChannel: OnboardingChannel = OnboardingChannel.BANKID,
)

data class SyncFromBankIdRequest(
    val bankIdSub: String,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val gender: Gender? = null,
    val birthplace: String? = null,
    val nationalities: List<String> = emptyList(),
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS
     * of a collection, so `{"idDocuments": [null]}` deserialises happily into a
     * `List<IdDocumentDto>` holding a null. Writing the type honestly is what makes
     * [requireIdDocuments] reachable instead of dead code.
     */
    val idDocuments: List<IdDocumentDto?> = emptyList(),
    val email: String? = null,
    val phone: String? = null,
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`;
     * no service-local mapper is added (#526).
     */
    fun requireIdDocuments(): List<IdDocumentDto> = idDocuments.mapIndexed { index, doc ->
        requireNotNull(doc) { "idDocuments[$index] must not be null" }
    }
}

data class SyncFromRobRequest(
    val robAifo: String,
    val permanentAddress: AddressDto? = null,
    val mailingAddress: AddressDto? = null,
)

data class UpdateContactRequest(
    val email: String? = null,
    val phone: String? = null,
    val preferredLanguage: String? = null,
    val dataBoxId: String? = null,
)

data class UpdateKycRequest(
    val kycLevel: KycLevel,
    val amlRiskScore: AmlRiskScore,
    val pepFlag: Boolean = false,
    val sanctionsFlag: Boolean = false,
)

data class ChangeStatusRequest(val status: PartyStatus, val reason: String? = null)

data class TransitionCaseRequest(
    val status: CaseStatus,
    val actor: String,
    val reasonCode: CaseReasonCode,
    val reason: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class AddRelationshipRequest(val role: PartyRole, val onboardingChannel: OnboardingChannel = OnboardingChannel.API)

data class TerminateRelationshipRequest(val reason: String? = null)

data class IdDocumentDto(
    val type: IdDocumentType,
    val number: String,
    val issuingCountry: String,
    val issuedAt: LocalDate? = null,
    val expiresAt: LocalDate? = null,
)

data class AddressDto(
    val street: String? = null,
    val houseNumber: String? = null,
    val city: String,
    val postalCode: String,
    val countryCode: String = "CZ",
    val ruianCode: String? = null,
)

data class PartyResponse(
    val id: UUID,
    val partyType: PartyType,
    val status: PartyStatus,
    val externalIds: List<ExternalIdResponse>,
    val coreAttributes: CoreAttributesResponse,
    val addressAttributes: AddressAttributesResponse?,
    val contactAttributes: ContactAttributesResponse,
    val kycAttributes: KycAttributesResponse,
    val relationships: List<RelationshipResponse>,
    val caseLifecycle: PartyCaseLifecycleResponse?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val version: Long,
)

data class PartyCaseLifecycleResponse(
    val caseId: UUID,
    val caseType: CaseType,
    val status: CaseStatus,
    val lastActor: String,
    val lastReasonCode: CaseReasonCode,
    val lastTransitionAt: OffsetDateTime,
    val metadata: Map<String, String>,
)

data class ExternalIdResponse(val type: ExternalIdType, val value: String, val verifiedAt: OffsetDateTime?)

data class CoreAttributesResponse(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val gender: Gender?,
    val birthplace: String?,
    val nationalities: List<String>,
    val idDocuments: List<IdDocumentDto>,
    val verificationSource: VerificationSource,
    val verifiedAt: OffsetDateTime,
)

data class AddressAttributesResponse(
    val permanentAddress: AddressDto?,
    val mailingAddress: AddressDto?,
    val robSyncedAt: OffsetDateTime?,
)

data class ContactAttributesResponse(
    val email: String?,
    val emailVerifiedAt: OffsetDateTime?,
    val phone: String?,
    val phoneVerifiedAt: OffsetDateTime?,
    val preferredLanguage: String,
    val dataBoxId: String?,
)

data class KycAttributesResponse(
    val kycLevel: KycLevel,
    val kycCompletedAt: OffsetDateTime?,
    val kycExpiresAt: OffsetDateTime?,
    val amlRiskScore: AmlRiskScore,
    val pepFlag: Boolean,
    val sanctionsFlag: Boolean,
    val lastAmlReviewAt: OffsetDateTime?,
)

data class RelationshipResponse(
    val id: UUID,
    val role: PartyRole,
    val status: RelationshipStatus,
    val onboardedAt: OffsetDateTime,
    val onboardingChannel: OnboardingChannel,
    val terminatedAt: OffsetDateTime?,
    val terminationReason: String?,
)

// ── Identity resolution DTOs (ADR-0072) ──────────────────────────────────────

/**
 * Request body for `POST /api/v1/parties/resolve`.
 * [birthNumberRaw] is the plaintext Czech RČ; pid-service reduces it to a blind index
 * immediately and never stores or logs the plaintext.  The field is optional: callers that
 * do not hold an RČ (foreign nationals, branch walk-ins) omit it and fall through to tier-2
 * normalized candidate matching.
 */
data class ResolvePartyRequest(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String? = null,
    val birthNumberRaw: String? = null,
    val nationalities: List<String> = emptyList(),
)

/** Body for `POST /api/v1/parties/{id}/external-ids` — link an identifier to an existing party. */
data class LinkExternalIdRequest(val type: ExternalIdType, val value: String)

/**
 * Response for `POST /api/v1/parties/resolve`.
 *
 * [decision] is one of `MATCH_EXISTING`, `NO_MATCH`, or `NEEDS_MANUAL_VERIFICATION`.
 * [partyId] is set only when `decision = MATCH_EXISTING`.
 * [caseId] is set when `decision = NEEDS_MANUAL_VERIFICATION` and the four-eyes wiring
 *   is active (currently null — PR4 of the ADR-0072 delivery order).
 * [candidates] is set when `decision = NEEDS_MANUAL_VERIFICATION`; masked for the
 *   cockpit review surface (must never be forwarded to the customer channel).
 */
data class ResolvePartyResponse(
    val decision: String,
    val partyId: UUID?,
    val caseId: UUID?,
    val candidates: List<CandidateSummaryResponse>?,
)

/** A masked candidate for the cockpit approval queue. PII-minimised. */
data class CandidateSummaryResponse(val partyId: UUID, val nameMasked: String, val birthYear: Int)

/** Request for `POST /api/v1/parties/register-identity` (issue #1294). */
data class RegisterIdentityRequest(
    val partyId: UUID,
    val partyType: PartyType = PartyType.NATURAL_PERSON,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String? = null,
    val birthNumberRaw: String? = null,
    val keycloakSub: String? = null,
    val nationalities: List<String> = emptyList(),
    /** Verified EUDI PID subject identifier (ADR-0094); written as the EUDI_PID_SUB blind index. */
    val eudiPidSub: String? = null,
)
