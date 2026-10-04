// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
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
    /**
     * The kernel [Money] (#11604): currency and amount in one value, held at the currency's own
     * minor-unit scale (EUR 2, JPY 0, KWD 3). Built at the API boundary, so a message that exists
     * always carries an ISO 4217 currency with a minor unit.
     */
    val amount: Money,
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
    /** ISO 4217 code of [amount], upper case — the `currency` column and wire field. */
    val currency: String get() = amount.currency.code

    /**
     * [amount] in the currency's minor units (cents for EUR, yen for JPY, fils for KWD) — the
     * `amount_minor_units` column. Exact: a [Money] is always at its currency's scale.
     */
    val amountMinorUnits: Long get() = amount.amount.unscaledValue().longValueExact()

    fun validate(): List<String> = buildList {
        if (senderBic.length !in 8..11) add("Invalid sender BIC: $senderBic")
        if (receiverBic.length !in 8..11) add("Invalid receiver BIC: $receiverBic")
        if (transactionReference.isBlank()) add("Transaction reference required")
        if (!amount.isPositive()) add("Amount must be positive")
        if (chargeCode !in setOf("OUR", "SHA", "BEN")) add("Invalid charge code: $chargeCode")
        try {
            LocalDate.parse(valueDate, VALUE_DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            add("Invalid valueDate '$valueDate': must be YYYYMMDD")
        }
    }

    companion object {
        /**
         * The [Money] a stored `amount_minor_units` + `currency` pair denotes, read at the
         * currency's own minor unit (not a fixed two decimals).
         */
        fun moneyOfMinorUnits(minorUnits: Long, currency: String): Money {
            val code = CurrencyCode.of(currency)
            return Money(BigDecimal.valueOf(minorUnits, code.defaultFractionDigits), code)
        }

        private val VALUE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
