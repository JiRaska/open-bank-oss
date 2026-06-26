// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.application.port.`in`

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import java.util.UUID

data class SendSwiftCommand(
    val idempotencyKey: String,
    val messageType: SwiftMessageType,
    val senderBic: String,
    val receiverBic: String,
    val transactionReference: String,
    val relatedReference: String?,
    val valueDate: String,
    val currency: String,
    val amountMinorUnits: Long,
    val orderingCustomerAccount: String?,
    /** Internal account UUID for the ordering customer — required for MT103 settlement booking (ADR-0108). */
    val orderingCustomerAccountId: UUID?,
    val orderingCustomerName: String?,
    val beneficiaryAccount: String,
    val beneficiaryName: String,
    val remittanceInfo: String?,
    val chargeCode: String = "SHA",
    val priority: SwiftPriority = SwiftPriority.NORMAL,
)

interface SwiftUseCase {
    suspend fun send(cmd: SendSwiftCommand): SwiftMessage
    suspend fun getById(id: UUID): SwiftMessage?
    suspend fun listAll(): List<SwiftMessage>
    suspend fun listByStatus(status: SwiftStatus): List<SwiftMessage>
    suspend fun acknowledge(id: UUID, ackRef: String): SwiftMessage
    suspend fun reject(id: UUID, reason: String): SwiftMessage
}
