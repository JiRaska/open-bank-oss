// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.`in`

import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.RegistryExtract
import java.time.LocalDate
import java.util.UUID

/** What the applicant declares when no free public register can answer for their scheme (manual attestation). */
data class DeclaredEntity(
    val legalName: String,
    val countryCode: String,
    val legalFormClass: String?,
    val addressLine1: String?,
    val city: String?,
    val postalCode: String?,
)

data class LookupCommand(val scheme: IdentifierScheme, val identifier: String, val declared: DeclaredEntity? = null)

data class StartCaseCommand(
    val scheme: IdentifierScheme,
    val identifier: String,
    val initiatorPartyId: UUID,
    val declared: DeclaredEntity? = null,
)

data class MatchInitiatorCommand(
    val caseId: UUID,
    val callerPartyId: UUID,
    val representativeIndex: Int?,
    val claimedName: String,
    val dateOfBirth: LocalDate?,
)

data class InviteCosignersCommand(val caseId: UUID, val callerPartyId: UUID, val representativeIndexes: List<Int>)

data class ClaimInvitationCommand(val token: String, val partyId: UUID)

data class SignCommand(val caseId: UUID, val signerPartyId: UUID, val signatureRef: String)

data class ResolveReviewCommand(val caseId: UUID, val requiredSignatures: Int, val operator: String)

data class RejectCaseCommand(val caseId: UUID, val reason: String, val operator: String)

interface RegistryLookupUseCase {
    /**
     * Null when the register answers "no such entity". An OUTAGE throws
     * `RegistryUnavailableException` instead, so the two can never be confused by a caller.
     */
    suspend fun lookup(cmd: LookupCommand): RegistryExtract?
}

// One method per state transition: the count belongs to the state machine, not to this interface.
@Suppress("TooManyFunctions")
interface BusinessOnboardingUseCase {
    suspend fun start(cmd: StartCaseCommand): BusinessOnboardingCase
    suspend fun get(caseId: UUID): BusinessOnboardingCase
    suspend fun listForParty(partyId: UUID): List<BusinessOnboardingCase>
    suspend fun listByStatus(status: CaseStatus, page: Int, size: Int): List<BusinessOnboardingCase>
    suspend fun matchInitiator(cmd: MatchInitiatorCommand): BusinessOnboardingCase
    suspend fun inviteCosigners(cmd: InviteCosignersCommand): BusinessOnboardingCase
    suspend fun claimInvitation(cmd: ClaimInvitationCommand): BusinessOnboardingCase
    suspend fun sign(cmd: SignCommand): BusinessOnboardingCase
    suspend fun abandon(caseId: UUID, callerPartyId: UUID): BusinessOnboardingCase
    suspend fun resolveReview(cmd: ResolveReviewCommand): BusinessOnboardingCase
    suspend fun reject(cmd: RejectCaseCommand): BusinessOnboardingCase

    /** Consumer entry: the entity party for some case reached ACTIVE. Idempotent; no-op when no case owns [entityPartyId]. */
    suspend fun entityPartyActivated(entityPartyId: UUID)

    /** Timer entry (Temporal): abandon the case if it is STILL in [expectedState]. Returns true when it did. */
    suspend fun abandonIfInState(caseId: UUID, expectedState: String, actor: String): Boolean
}
