// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

enum class SwiftMessageType { MT103, MT202, MT900, MT910, MT940, MT950, MT199 }
enum class SwiftStatus { PENDING, VALIDATED, SENT, ACKNOWLEDGED, REJECTED, FAILED, COMPLETED }
enum class SwiftPriority { NORMAL, URGENT, SYSTEM }

data class SwiftMessage(
    val id: UUID,
    val idempotencyKey: String,
    val messageType: SwiftMessageType,
    val senderBic: String,
    val receiverBic: String,
    val transactionReference: String,
    val relatedReference: String?,
    val valueDate: String, // YYYYMMDD
    val currency: String,
    val amountMinorUnits: Long,
    val orderingCustomerAccount: String?,
    val orderingCustomerAccountId: UUID?,
    val orderingCustomerName: String?,
    val beneficiaryAccount: String,
    val beneficiaryName: String,
    val remittanceInfo: String?,
    val chargeCode: String, // OUR, SHA, BEN
    val priority: SwiftPriority,
    val status: SwiftStatus,
    val rawMt: String?, // raw SWIFT MT message
    val ackReceivedAt: Instant?,
    val rejectionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
) {
    fun validate(): List<String> = buildList {
        if (senderBic.length !in 8..11) add("Invalid sender BIC: $senderBic")
        if (receiverBic.length !in 8..11) add("Invalid receiver BIC: $receiverBic")
        if (transactionReference.isBlank()) add("Transaction reference required")
        if (amountMinorUnits <= 0) add("Amount must be positive")
        if (chargeCode !in setOf("OUR", "SHA", "BEN")) add("Invalid charge code: $chargeCode")
        try {
            LocalDate.parse(valueDate, VALUE_DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            add("Invalid valueDate '$valueDate': must be YYYYMMDD")
        }
    }

    companion object {
        private val VALUE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
