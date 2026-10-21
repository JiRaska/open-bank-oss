// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.finance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.risk.domain.valuation.FlowDirection
import com.openbank.risk.domain.valuation.StoredCashFlow
import io.quarkus.runtime.annotations.RegisterForReflection
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One row of treasury's cash-flow ledger as `treasury.cashflow.generated` carries it (ADR-0344
 * contract `CashFlow`). Only the fields valuation reads are bound; the rest are ignored.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class CashFlowDto(
    val legIndex: Int? = null,
    val periodIndex: Int? = null,
    val kind: String? = null,
    val direction: String? = null,
    val currency: String? = null,
    val accrualStart: String? = null,
    val accrualEnd: String? = null,
    val paymentDate: String? = null,
    val amount: BigDecimal? = null,
    val rateSource: String? = null,
    val state: String? = null,
)

object CashFlowJson {
    fun toDomain(i: Int, d: CashFlowDto): StoredCashFlow {
        fun <T> req(v: T?, f: String): T = requireNotNull(v) { "flows[$i].$f is required" }
        return StoredCashFlow(
            legIndex = req(d.legIndex, "legIndex"),
            periodIndex = req(d.periodIndex, "periodIndex"),
            kind = CashFlowKind.entries.firstOrNull { it.name == req(d.kind, "kind") }
                ?: throw IllegalArgumentException("flows[$i].kind '${d.kind}' is unknown"),
            direction = FlowDirection.entries.firstOrNull { it.name == req(d.direction, "direction") }
                ?: throw IllegalArgumentException("flows[$i].direction '${d.direction}' is not IN or OUT"),
            currency = req(d.currency, "currency").trim().uppercase(),
            paymentDate = LocalDate.parse(req(d.paymentDate, "paymentDate")),
            accrualStart = d.accrualStart?.let(LocalDate::parse),
            accrualEnd = d.accrualEnd?.let(LocalDate::parse),
            amount = d.amount,
            rateSource = d.rateSource,
            state = req(d.state, "state").trim().uppercase(),
        )
    }

    fun toDto(f: StoredCashFlow) = CashFlowDto(
        legIndex = f.legIndex,
        periodIndex = f.periodIndex,
        kind = f.kind.name,
        direction = f.direction.name,
        currency = f.currency,
        accrualStart = f.accrualStart?.toString(),
        accrualEnd = f.accrualEnd?.toString(),
        paymentDate = f.paymentDate.toString(),
        amount = f.amount,
        rateSource = f.rateSource,
        state = f.state,
    )
}
