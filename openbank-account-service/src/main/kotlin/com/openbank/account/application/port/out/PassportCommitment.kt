// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.ProductRevisionPin
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class PassportOperation { OPEN, ACTIVATE }

data class PassportIntent(
    val key: String,
    val operationId: UUID,
    val accountId: UUID,
    val grantId: UUID,
    val partyId: UUID,
    val operation: PassportOperation,
    val binding: String,
    val pin: ProductRevisionPin,
)

/** Minimal prepared account data. The name and raw evidence used for screening stay out of the queue. */
data class PassportOpeningDraft(
    val iban: String,
    val productId: UUID,
    val accountType: AccountType,
    val currency: String,
    val sanctionsScreenedAt: Instant,
    val sanctionsStatus: String,
    val termsVersion: String?,
    val termsUrl: String?,
    val termsEffectiveFrom: LocalDate?,
    val synthetic: Boolean = false,
)

data class PassportOpeningJob(val intent: PassportIntent, val draft: PassportOpeningDraft)

interface PassportCommitment {
    suspend fun find(key: String): PassportIntent?

    suspend fun openingFor(accountId: UUID): PassportIntent?

    /** Insert once and return the winning durable intent. */
    suspend fun prepare(intent: PassportIntent): PassportIntent

    suspend fun stageOpening(intent: PassportIntent, draft: PassportOpeningDraft)

    suspend fun claimOpenings(limit: Int): List<PassportOpeningJob>

    /** Authoritative read of an exact consumed permit. Never consumes a new permit. */
    suspend fun hasReceipt(intent: PassportIntent): Boolean

    /** Catalogue commits the one-use admission, or returns the exact earlier receipt. */
    suspend fun admit(intent: PassportIntent)
}
