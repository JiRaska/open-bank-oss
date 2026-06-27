// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class RateType { SPOT, FORWARD, INDICATIVE, INTERBANK }
enum class RateSource { ECB, REUTERS, BLOOMBERG, INTERNAL, CNB }

data class FxRate(
    val id: UUID,
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
    val rateType: RateType,
    val source: RateSource,
    val validFrom: Instant,
    val validTo: Instant,
    val createdAt: Instant
) {
    val pair: String get() = "$baseCurrency/$quoteCurrency"
    val midRate: BigDecimal get() = (bidRate + askRate).divide(BigDecimal.TWO)
    val spread: BigDecimal get() = askRate - bidRate
    fun isValid(at: Instant = Instant.EPOCH) = at.isAfter(validFrom) && at.isBefore(validTo)
}

data class FxConversion(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
    val toAmountMinorUnits: Long,
    val appliedRate: BigDecimal,
    val feeMinorUnits: Long,
    val rateId: UUID,
    val status: FxConversionStatus,
    val createdAt: Instant,
    val settledAt: Instant?
)

enum class FxConversionStatus { PENDING, SETTLED, FAILED, REVERSED }
