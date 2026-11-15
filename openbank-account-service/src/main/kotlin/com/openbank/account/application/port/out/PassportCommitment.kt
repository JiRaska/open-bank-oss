// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.ProductRevisionPin
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

interface PassportCommitment {
    suspend fun find(key: String): PassportIntent?

    suspend fun openingFor(accountId: UUID): PassportIntent?

    /** Insert once and return the winning durable intent. */
    suspend fun prepare(intent: PassportIntent): PassportIntent

    /** Catalogue commits the one-use admission, or returns the exact earlier receipt. */
    suspend fun admit(intent: PassportIntent)
}
