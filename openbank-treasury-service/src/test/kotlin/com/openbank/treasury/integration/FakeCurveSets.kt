// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.CurveSetPort
import com.openbank.treasury.domain.model.CurvePillar
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.MarketCurve
import com.openbank.treasury.domain.model.QuoteUnavailableException
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The risk engine's curve sets, scripted per test (ADR-0315 D9). Replaces [CurveSetPort] in every
 * `@QuarkusTest`, so no test reaches for a real risk engine; the wire shape is pinned by
 * `TreasuryRiskCurvePactConsumerTest` and replayed by the risk engine instead.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeCurveSets : CurveSetPort {
    @Volatile
    var set: CurveSetView? = flat("0.035")

    @Volatile
    var down: Boolean = false

    override suspend fun latest(): CurveSetView? =
        if (down) throw QuoteUnavailableException("risk engine unreachable: scripted outage") else set

    fun reset() {
        set = flat("0.035")
        down = false
    }

    companion object {
        val ID: UUID = UUID.fromString("0191c0de-0000-7000-8000-00000000c5e7")

        /** A flat CZEONIA and ESTR zero curve at [zero] (decimal), as of today (UTC). */
        fun flat(zero: String): CurveSetView {
            val asOf = LocalDate.now(ZoneOffset.UTC)
            val pillars =
                listOf(
                    CurvePillar(asOf.plusDays(1), BigDecimal(zero)),
                    CurvePillar(asOf.plusDays(365), BigDecimal(zero)),
                )
            return CurveSetView(
                ID,
                asOf,
                "synthetic",
                listOf(MarketCurve("CZEONIA", "CZK", pillars), MarketCurve("ESTR", "EUR", pillars)),
            )
        }
    }
}
