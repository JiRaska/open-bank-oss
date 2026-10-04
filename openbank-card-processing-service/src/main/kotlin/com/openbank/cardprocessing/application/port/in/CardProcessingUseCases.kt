// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.`in`

import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import java.util.UUID

/** One authorisation as the acquirer presents it. */
data class AuthorizationCommand(
    val cardId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String?,
    val merchantName: String?,
    val merchantCountry: String?,
    val networkReference: String?,
    val idempotencyKey: String,
)

data class PresentmentCommand(
    val authorizationId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val idempotencyKey: String,
)

interface CardProcessingUseCase {
    /** Decide, record and hold. Returns the authorisation in its decided state, approved or declined. */
    suspend fun authorize(command: AuthorizationCommand): CardAuthorization

    /** Apply a clearing presentment and post the cleared amount to the books. */
    suspend fun clear(command: PresentmentCommand): PresentmentOutcome

    /** Release the remaining hold at the acquirer's request. */
    suspend fun reverse(authorizationId: UUID): PresentmentOutcome

    suspend fun findById(id: UUID): CardAuthorization?

    suspend fun findByCard(cardId: UUID, limit: Int): List<CardAuthorization>

    /** Releases every hold past its expiry. Returns how many were released. Driven by the sweep. */
    suspend fun releaseExpiredHolds(limit: Int): Int
}
