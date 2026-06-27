// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.`in`

import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseStatus
import com.openbank.pid.domain.model.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

interface CreatePartyUseCase {
    suspend fun createParty(command: CreatePartyCommand): Party
}

interface GetPartyUseCase {
    suspend fun getById(id: UUID): Party
    suspend fun getByExternalId(type: ExternalIdType, value: String): Party
    suspend fun search(query: PartySearchQuery): List<Party>
}

interface UpdatePartyUseCase {
    suspend fun updateContact(command: UpdateContactCommand): Party
    suspend fun syncFromBankId(command: SyncFromBankIdCommand): Party
    suspend fun syncFromRob(command: SyncFromRobCommand): Party
    suspend fun updateKyc(command: UpdateKycCommand): Party
    suspend fun changeStatus(command: ChangePartyStatusCommand): Party
    suspend fun transitionCase(command: TransitionPartyCaseCommand): Party
    suspend fun linkCaseEvidence(command: LinkCaseEvidenceCommand): Party
    suspend fun linkExternalId(command: LinkExternalIdCommand): Party
}

interface ManageRelationshipUseCase {
    suspend fun addRelationship(command: AddRelationshipCommand): PartyRelationship
    suspend fun terminateRelationship(command: TerminateRelationshipCommand): PartyRelationship
}

data class CreatePartyCommand(
    val partyType: PartyType,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthNumberEncrypted: String?,
    /**
     * Plaintext Czech RČ (rodné číslo), supplied by the caller so pid-service can compute and
     * store the blind index (HMAC-SHA256 hex) in `party_external_ids(BIRTH_NUMBER)`.
     * The value is never stored or logged — it is reduced to a blind index within the
     * use-case and discarded (ADR-0072).
     */
    val birthNumberRaw: String? = null,
    val nationalities: List<String>,
    val verificationSource: VerificationSource,
    val bankIdSub: String?,
    val initialRole: PartyRole,
    val onboardingChannel: OnboardingChannel,
)

data class SyncFromBankIdCommand(
    val partyId: UUID,
    val bankIdSub: String,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthNumberEncrypted: String?,
    val gender: Gender?,
    val birthplace: String?,
    val nationalities: List<String>,
    val idDocuments: List<IdDocument>,
    val email: String?,
    val phone: String?,
)

data class SyncFromRobCommand(
    val partyId: UUID,
    val robAifo: String,
    val permanentAddress: Address?,
    val mailingAddress: Address?,
    val syncedAt: OffsetDateTime,
)

data class UpdateContactCommand(
    val partyId: UUID,
    val email: String?,
    val phone: String?,
    val preferredLanguage: String?,
    val dataBoxId: String?,
)

data class UpdateKycCommand(
    val partyId: UUID,
    val kycLevel: KycLevel,
    val amlRiskScore: AmlRiskScore,
    val pepFlag: Boolean,
    val sanctionsFlag: Boolean,
)

data class ChangePartyStatusCommand(val partyId: UUID, val newStatus: PartyStatus, val reason: String?)

data class TransitionPartyCaseCommand(
    val partyId: UUID,
    val toStatus: CaseStatus,
    val actor: String,
    val reasonCode: CaseReasonCode,
    val reason: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class LinkCaseEvidenceCommand(
    val partyId: UUID,
    val evidenceRef: String,
    val actor: String,
    val linkedAt: OffsetDateTime = OffsetDateTime.MIN,
    val metadata: Map<String, String> = emptyMap(),
)

data class AddRelationshipCommand(val partyId: UUID, val role: PartyRole, val onboardingChannel: OnboardingChannel)

data class TerminateRelationshipCommand(val partyId: UUID, val relationshipId: UUID, val reason: String?)

/**
 * Link an additional external identifier (e.g. a second Keycloak sub) to an existing party —
 * the identity-unification merge of ADR-0072 §5. Idempotent for a (type, value) already on the
 * party; rejected if the same (type, value) is already linked to a *different* party.
 */
data class LinkExternalIdCommand(val partyId: UUID, val type: ExternalIdType, val value: String)

data class PartySearchQuery(
    val givenName: String? = null,
    val familyName: String? = null,
    val birthdate: LocalDate? = null,
    val email: String? = null,
    val role: PartyRole? = null,
    val status: PartyStatus? = null,
    val limit: Int = 20,
    val afterId: UUID? = null,
)

// ── Identity resolution (ADR-0072) ─────────────────────────────────────────

/**
 * Resolve whether an applicant already exists as a party before creating one.
 * Called by every party-creating path (onboarding-start, operator create, BankID sync).
 * Only a [ResolutionResult.NoMatch] permits creating a new party.
 */
interface ResolveIdentityUseCase {
    suspend fun resolve(command: ResolveIdentityCommand): ResolutionResult
}

/**
 * Direct party lookup by a pre-computed RČ blind index (ADR-0072).
 *
 * The caller computes the index externally via `BlindIndex.compute(pepper, rodneCislo.canonical)`.
 * This is the machine-to-machine endpoint for downstream services that already hold a blind index
 * but do not carry the full resolution context (given name / family name / birthdate).
 */
interface ResolveByIndexUseCase {
    /**
     * Returns the [UUID] of the party whose BIRTH_NUMBER external-id matches [blindIndex],
     * or `null` when no party is found.
     */
    suspend fun resolveByIndex(blindIndex: String): UUID?
}

/**
 * Input for identity resolution.  [birthNumberRaw] is the plaintext Czech RČ supplied by the
 * caller (e.g. BankID claim).  pid-service immediately reduces it to a blind index and never
 * stores or logs the plaintext.
 */
data class ResolveIdentityCommand(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String? = null,
    val birthNumberRaw: String? = null,
    val nationalities: List<String> = emptyList(),
    /**
     * The VERIFIED EUDI PID subject identifier (ADR-0094 tier-0), or null. The `…Verified` suffix is a
     * contract: only [com.openbank.pid.application.usecase.EudiVerifyPresentationService] (post crypto
     * verification) may set it — never the public /resolve request DTO. The resolver blind-indexes it.
     */
    val eudiPidSubVerified: String? = null,
)

/** Three-tier identity resolution verdict (ADR-0072 §4). */
sealed interface ResolutionResult {
    /** An existing party with the same deterministic key was found. No new party needed. */
    data class MatchExisting(val partyId: UUID) : ResolutionResult

    /** No candidate found — a new party may be created. */
    data object NoMatch : ResolutionResult

    /**
     * Ambiguous match (same RČ + divergent attributes, or normalized-name namesake).
     * A four-eyes case is opened; [caseId] is null if the four-eyes wiring is not yet active
     * (PR4 of the ADR-0072 delivery order).
     */
    data class NeedsManualVerification(
        val caseId: UUID?,
        val candidates: List<CandidateSummary>,
        val trigger: VerificationTrigger,
    ) : ResolutionResult
}

// VerificationTrigger now lives in the domain layer (com.openbank.pid.domain.model), imported via
// the `domain.model.*` wildcard above — the four-eyes case aggregate is built around it.

/** A masked candidate for the cockpit review surface — never returned to the customer channel. */
data class CandidateSummary(
    val partyId: UUID,
    /** Family initial + given initial, e.g. "N. J." (PII-minimised for the cockpit preview). */
    val nameMasked: String,
    val birthYear: Int,
)

// ── Identity index registration (issue #1294) ──────────────────────────────

/**
 * Register/refresh an identity in the pid index after a party is created in party-service, under the
 * same [partyId]. Writes the RČ blind index so tier-1 dedup has data. Idempotent; emits no party event.
 */
interface RegisterIdentityUseCase {
    suspend fun register(command: RegisterIdentityCommand): Party
}

/**
 * Input for [RegisterIdentityUseCase]. [partyId] is party-service's id (shared, not generated here).
 * [birthNumberRaw] is the plaintext Czech RČ; pid reduces it to the blind index and never stores it.
 */
data class RegisterIdentityCommand(
    val partyId: UUID,
    val partyType: PartyType,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String? = null,
    val birthNumberRaw: String? = null,
    val keycloakSub: String? = null,
    val nationalities: List<String> = emptyList(),
    /**
     * The VERIFIED EUDI PID subject identifier (ADR-0094). When present, its blind index is written as
     * an EUDI_PID_SUB external-id so tier-0 deterministically recognises the wallet holder next time.
     * Set only after cryptographic verification (EudiVerifyPresentationService); never the raw value.
     */
    val eudiPidSubVerified: String? = null,
)
