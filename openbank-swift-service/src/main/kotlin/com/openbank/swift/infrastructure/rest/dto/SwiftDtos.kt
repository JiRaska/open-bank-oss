// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.infrastructure.rest.dto

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import java.time.Instant
import java.util.UUID

data class SwiftMessageResponse(
    val id: UUID,
    val messageType: SwiftMessageType,
    val senderBic: String,
    val receiverBic: String,
    val amount: Double,
    val currency: String,
    val status: SwiftStatus,
    val createdAt: Instant,
    val reference: String,
)

fun SwiftMessage.toResponse() = SwiftMessageResponse(
    id = id,
    messageType = messageType,
    senderBic = senderBic,
    receiverBic = receiverBic,
    amount = amountMinorUnits / 100.0,
    currency = currency,
    status = status,
    createdAt = createdAt,
    reference = transactionReference,
)
