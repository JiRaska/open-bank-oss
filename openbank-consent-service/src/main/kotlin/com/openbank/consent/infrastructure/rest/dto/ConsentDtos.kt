// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest.dto

import com.openbank.consent.domain.model.*
import java.time.OffsetDateTime
import java.util.UUID

data class CreateConsentRequest(
    val partyId: UUID,
    val granteeId: String,
    val granteeType: GranteeType,
    val granteeName: String,
    val scopes: Set<ConsentScope>,
    val accountIbans: List<String>?,
    val validTo: OffsetDateTime,
    val redirectUri: String?,
    val tppTransactionId: String?
)

data class RevokeConsentRequest(
    val reason: String
)

data class ValidateConsentRequest(
    val granteeId: String,
    val requiredScope: ConsentScope,
    val accountIban: String?
)

data class ConsentResponse(
    val id: UUID,
    val partyId: UUID,
    val granteeId: String,
    val granteeType: GranteeType,
    val granteeName: String,
    val scopes: Set<ConsentScope>,
    val accountIbans: List<String>?,
    val status: ConsentStatus,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(c: Consent) = ConsentResponse(
            id = c.id,
            partyId = c.partyId,
            granteeId = c.granteeId,
            granteeType = c.granteeType,
            granteeName = c.granteeName,
            scopes = c.scopes,
            accountIbans = c.accountIbans,
            status = c.status,
            validFrom = c.validFrom,
            validTo = c.validTo,
            createdAt = c.createdAt
        )
    }
}

data class ConsentValidationResponse(
    val valid: Boolean,
    val reason: String?,
    val code: String?
)
