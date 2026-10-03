// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest.dto

import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.InvalidMoneyReason
import com.openbank.libs.domain.money.Money
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import java.math.BigDecimal
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
    // Major units at the currency's own scale (#11604) — was a fixed `/ 100.0`, which read JPY
    // 100x too small and KWD 10x too large. Unchanged for every two-decimal currency.
    amount = amount.amount.toDouble(),
    currency = currency,
    status = status,
    createdAt = createdAt,
    reference = transactionReference,
)

/**
 * The `POST /api/v1/swift` body. Same JSON members as before (#11604); the only difference from
 * the [SendSwiftCommand] it used to be is that `currency` + `amountMinorUnits` are read as raw
 * values and turned into a kernel [Money] by [toCommand], so a refusal happens HERE — before the
 * idempotency lookup, the row, the outbox event or any downstream call.
 *
 * `amountMinorUnits` is read as a [BigDecimal] rather than a `Long` so a fractional minor unit
 * (`150.5`) is refused instead of being silently truncated by Jackson's float-to-int coercion.
 */
data class SendSwiftRequest(
    val idempotencyKey: String,
    val messageType: SwiftMessageType,
    val senderBic: String,
    val receiverBic: String,
    val transactionReference: String,
    val relatedReference: String?,
    val valueDate: String,
    val currency: String?,
    val amountMinorUnits: BigDecimal?,
    val orderingCustomerAccount: String?,
    val orderingCustomerAccountId: UUID?,
    val orderingCustomerName: String?,
    val beneficiaryAccount: String,
    val beneficiaryName: String,
    val remittanceInfo: String?,
    val chargeCode: String = "SHA",
    val priority: SwiftPriority = SwiftPriority.NORMAL,
) {
    fun toCommand() = SendSwiftCommand(
        idempotencyKey = idempotencyKey,
        messageType = messageType,
        senderBic = senderBic,
        receiverBic = receiverBic,
        transactionReference = transactionReference,
        relatedReference = relatedReference,
        valueDate = valueDate,
        amount = inboundMoney(amountMinorUnits, currency),
        orderingCustomerAccount = orderingCustomerAccount,
        orderingCustomerAccountId = orderingCustomerAccountId,
        orderingCustomerName = orderingCustomerName,
        beneficiaryAccount = beneficiaryAccount,
        beneficiaryName = beneficiaryName,
        remittanceInfo = remittanceInfo,
        chargeCode = chargeCode,
        priority = priority,
    )
}

/**
 * Builds the kernel [Money] from a MINOR-unit amount. SWIFT is cross-border, so there is no
 * currency allow-list: any ISO 4217 currency with a minor unit is accepted (JPY 0dp, EUR 2dp,
 * KWD/BHD 3dp). The currency is resolved first (refusal: `CURRENCY_UNSUPPORTED` on `currency`);
 * the minor units are then shifted by THAT currency's own fraction digits and offered to
 * [Money.parseInbound], so a fractional minor unit is `AMOUNT_SCALE_EXCEEDED` and a magnitude the
 * kernel (or the BIGINT column) cannot hold is `VALIDATION_ERROR`, both on `amountMinorUnits`. The value is never echoed.
 */
internal fun inboundMoney(amountMinorUnits: BigDecimal?, currency: String?): Money {
    val unit = Money.parseInbound(BigDecimal.ZERO, currency, amountField = AMOUNT_FIELD)
    val money = try {
        Money.parseInbound(
            amountMinorUnits?.movePointLeft(unit.currency.defaultFractionDigits),
            currency,
            amountField = AMOUNT_FIELD,
        )
    } catch (e: InvalidMoneyException) {
        throw minorUnitsWording(e)
    }
    // The `amount_minor_units` column is a BIGINT: a value the kernel can hold but a Long cannot
    // is out of range here, not a 500 at the INSERT.
    if (money.amount.unscaledValue().bitLength() >= Long.SIZE_BITS) {
        throw InvalidMoneyException(
            InvalidMoneyReason.AMOUNT_OUT_OF_RANGE,
            "amountMinorUnits does not fit a signed 64-bit integer",
            clientMessage = "Amount is out of the supported range",
            field = AMOUNT_FIELD,
        )
    }
    return money
}

/**
 * The kernel's scale text speaks of decimal places of the MAJOR amount; this field is minor units,
 * so say what is actually wrong with it. Reason, code and field stay the kernel's.
 */
private fun minorUnitsWording(e: InvalidMoneyException): InvalidMoneyException =
    if (e.reason != InvalidMoneyReason.SCALE_EXCEEDED) {
        e
    } else {
        InvalidMoneyException(e.reason, e.message.orEmpty(), FRACTIONAL_MINOR_UNITS, AMOUNT_FIELD, e)
    }

private const val AMOUNT_FIELD = "amountMinorUnits"
private const val FRACTIONAL_MINOR_UNITS = "Amount in minor units must be a whole number"

/**
 * The wire shape of a [SwiftMessage] on `POST /api/v1/swift`, `GET /api/v1/swift/{id}` and
 * `GET /api/v1/swift/status/{status}`. Those endpoints used to serialise the domain object itself;
 * now that it carries a [Money], this view keeps the published members and their order exactly
 * as they were (`currency` + `amountMinorUnits`, no `amount` object).
 */
data class SwiftMessageView(
    val id: UUID,
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
    val orderingCustomerAccountId: UUID?,
    val orderingCustomerName: String?,
    val beneficiaryAccount: String,
    val beneficiaryName: String,
    val remittanceInfo: String?,
    val chargeCode: String,
    val priority: SwiftPriority,
    val status: SwiftStatus,
    val rawMt: String?,
    val ackReceivedAt: Instant?,
    val rejectionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

fun SwiftMessage.toView() = SwiftMessageView(
    id = id,
    idempotencyKey = idempotencyKey,
    messageType = messageType,
    senderBic = senderBic,
    receiverBic = receiverBic,
    transactionReference = transactionReference,
    relatedReference = relatedReference,
    valueDate = valueDate,
    currency = currency,
    amountMinorUnits = amountMinorUnits,
    orderingCustomerAccount = orderingCustomerAccount,
    orderingCustomerAccountId = orderingCustomerAccountId,
    orderingCustomerName = orderingCustomerName,
    beneficiaryAccount = beneficiaryAccount,
    beneficiaryName = beneficiaryName,
    remittanceInfo = remittanceInfo,
    chargeCode = chargeCode,
    priority = priority,
    status = status,
    rawMt = rawMt,
    ackReceivedAt = ackReceivedAt,
    rejectionReason = rejectionReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version,
)
