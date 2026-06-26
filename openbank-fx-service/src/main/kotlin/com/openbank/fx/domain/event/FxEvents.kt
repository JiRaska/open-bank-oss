// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.domain.event

import java.math.BigDecimal; import java.time.Instant; import java.util.UUID

sealed class FxEvent { abstract val occurredAt: Instant }
data class FxRatePublished(val rateId: UUID, val pair: String, val midRate: BigDecimal,
    override val occurredAt: Instant = Instant.EPOCH) : FxEvent()
data class FxConversionExecuted(val conversionId: UUID, val partyId: UUID,
    val fromCurrency: String, val toCurrency: String,
    val fromAmount: Long, val toAmount: Long, val rate: BigDecimal,
    override val occurredAt: Instant = Instant.EPOCH) : FxEvent()
