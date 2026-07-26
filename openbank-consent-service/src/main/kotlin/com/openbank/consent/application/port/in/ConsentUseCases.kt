// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.port.`in`

import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentValidationResult
import com.openbank.consent.domain.model.GranteeType
import java.time.OffsetDateTime
import java.util.UUID

data class CreateConsentCommand(
    val partyId: UUID,
    val granteeId: String,
    val granteeType: GranteeType,
    val granteeName: String,
    val scopes: Set<ConsentScope>,
    val accountIbans: List<String>?,
    val validTo: OffsetDateTime,
    val redirectUri: String?,
    val tppTransactionId: String?,
    val ipAddress: String?,
    val userAgent: String?,
)

// expectedGranteeId is null for a human/operator-initiated revoke (no cross-check, matches
// today's behavior); an M2M caller authorized via the grantee-scoped OPA rule (ADR-0206) must
// pass its own granteeId so the use case can confirm the consent it's revoking is actually the
// one that rule authorized — the OPA resource check alone can't see the DB row.
data class RevokeConsentCommand(
    val consentId: UUID,
    val partyId: UUID,
    val reason: String,
    val expectedGranteeId: String? = null,
)

data class ValidateConsentCommand(
    val consentId: UUID,
    val granteeId: String,
    val requiredScope: ConsentScope,
    val accountIban: String?,
)

interface CreateConsentUseCase {
    suspend fun createConsent(command: CreateConsentCommand): Consent
}

interface RevokeConsentUseCase {
    suspend fun revokeConsent(command: RevokeConsentCommand): Consent
}

interface GetConsentUseCase {
    suspend fun getConsent(consentId: UUID): Consent
    suspend fun listConsentsForParty(partyId: UUID): List<Consent>
    suspend fun listConsentsForGrantee(granteeId: String): List<Consent>
}

interface ValidateConsentUseCase {
    suspend fun validateConsent(command: ValidateConsentCommand): ConsentValidationResult
}

interface ActivateConsentUseCase {
    suspend fun activateConsent(consentId: UUID, scaSessionId: UUID): Consent
    suspend fun rejectConsent(consentId: UUID, reason: String): Consent
}
