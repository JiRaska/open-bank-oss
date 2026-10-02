// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest.dto

import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import java.time.Instant
import java.util.UUID

/**
 * An authorisation as the acquirer presents it.
 *
 * `amountMinorUnits` is a `Long`, matching the scheme message. A decimal here would invite a
 * rounding decision at the edge that has no correct answer.
 */
data class AuthorizationRequestDto(
    val cardId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String? = null,
    val merchantName: String? = null,
    val merchantCountry: String? = null,
    val networkReference: String? = null,
)

data class PresentmentRequestDto(val amountMinorUnits: Long, val currencyCode: String)

data class AuthorizationResponseDto(
    val id: UUID,
    val cardId: UUID,
    val accountId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val status: String,
    val category: String,
    val declineReason: String?,
    val clearedAmountMinorUnits: Long,
    val heldAmountMinorUnits: Long,
    val merchantName: String?,
    val merchantCountry: String?,
    val networkReference: String?,
    val authorizedAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun of(a: CardAuthorization) = AuthorizationResponseDto(
            id = a.id,
            cardId = a.cardId,
            accountId = a.accountId,
            amountMinorUnits = a.amountMinorUnits,
            currencyCode = a.currencyCode,
            channel = a.channel,
            status = a.status.name,
            category = a.category,
            declineReason = a.declineReason,
            clearedAmountMinorUnits = a.clearedAmountMinorUnits,
            // Derived, so a client never has to subtract two numbers and get a different answer
            // than the service would.
            heldAmountMinorUnits = a.heldAmountMinorUnits,
            merchantName = a.merchantName,
            merchantCountry = a.merchantCountry,
            networkReference = a.networkReference,
            authorizedAt = a.authorizedAt,
            expiresAt = a.expiresAt,
        )
    }
}

data class AuthorizationListResponse(val data: List<AuthorizationResponseDto>, val count: Int)

data class RefusalResponse(val reason: String, val message: String)
