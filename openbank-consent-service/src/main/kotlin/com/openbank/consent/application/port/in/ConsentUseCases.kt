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
    val userAgent: String?
)

data class RevokeConsentCommand(
    val consentId: UUID,
    val partyId: UUID,
    val reason: String
)

data class ValidateConsentCommand(
    val consentId: UUID,
    val granteeId: String,
    val requiredScope: ConsentScope,
    val accountIban: String?
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
