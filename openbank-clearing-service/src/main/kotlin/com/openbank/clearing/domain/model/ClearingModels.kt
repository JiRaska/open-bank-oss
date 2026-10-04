// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.domain.model

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class ClearingStatus { PENDING, IN_CLEARING, SETTLED, FAILED, REVERSED }
enum class SettlementType { GROSS, NET, DEFERRED_NET }
enum class PaymentRail { SEPA_SCT, SEPA_SCT_INST, SWIFT, DOMESTIC, INTERNAL }

data class ClearingBatch(
    val id: UUID = UUID.randomUUID(),
    val batchReference: String,
    val rail: PaymentRail,
    val settlementType: SettlementType = SettlementType.NET,
    val status: ClearingStatus = ClearingStatus.PENDING,
    val totalDebit: BigDecimal = BigDecimal.ZERO,
    val totalCredit: BigDecimal = BigDecimal.ZERO,
    val netPosition: BigDecimal = BigDecimal.ZERO,
    val currency: String = "EUR",
    val itemCount: Int = 0,
    val cycleId: String? = null,
    val settlementDate: LocalDate? = null,
    val settledAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/**
 * What one clearing cycle produced (#11974). A clearing batch is per (rail, currency): the cycle
 * partitions the rail's pending items by currency and nets each currency in its own [batches]
 * entry, because netting and the net-settlement journal are only meaningful within one currency.
 *
 * [unsettleablePending] counts, per currency, items left PENDING because no settlement GL pair
 * exists for that currency — they are not batched (a batch could never post its settlement leg),
 * not failed (seeding the GL makes them settle on the next cycle), and are reported, not dropped.
 */
data class ClearingCycleResult(
    val cycleId: String,
    val rail: PaymentRail,
    val batches: List<ClearingBatch>,
    val unsettleablePending: Map<String, Long> = emptyMap(),
)

data class ClearingItem(
    val id: UUID = UUID.randomUUID(),
    val batchId: UUID,
    val paymentId: UUID,
    val paymentReference: String,
    val debtorIban: String,
    val creditorIban: String,
    val debtorBic: String? = null,
    val creditorBic: String? = null,
    val amount: BigDecimal,
    val currency: String = "EUR",
    val status: ClearingStatus = ClearingStatus.PENDING,
    /** Monotonic aggregate revision used by outbox consumers for replay-safe evidence. */
    val revision: Long = 0,
    val valueDate: LocalDate? = null,
    val endToEndId: String? = null,
    val remittanceInfo: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class SettlementPosition(
    val id: UUID = UUID.randomUUID(),
    val participantBic: String,
    val currency: String = "EUR",
    val cycleId: String,
    val grossDebit: BigDecimal = BigDecimal.ZERO,
    val grossCredit: BigDecimal = BigDecimal.ZERO,
    val netPosition: BigDecimal = BigDecimal.ZERO,
    val settled: Boolean = false,
    val settledAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
)

/**
 * A payment entering clearing, after the inbound boundary validated it (#11604): [amount] is a
 * kernel [Money], so its scale already fits the currency's minor unit and the currency is an
 * ISO 4217 code with one. The REST body is `SubmitPaymentRequest` in the REST adapter.
 */
data class SubmitPaymentCommand(
    val paymentId: UUID,
    val paymentReference: String,
    val debtorIban: String,
    val creditorIban: String,
    val debtorBic: String? = null,
    val creditorBic: String? = null,
    val amount: Money,
    val rail: PaymentRail = PaymentRail.SEPA_SCT,
    val valueDate: LocalDate? = null,
    val endToEndId: String? = null,
    val remittanceInfo: String? = null,
)
