// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.MinReservesAnalysis
import com.openbank.risk.domain.reserves.ReserveClass
import com.openbank.risk.domain.reserves.ReserveLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private const val RESERVE_MONEY_SCALE = 2

private fun BigDecimal.reserveMoney(): BigDecimal = setScale(RESERVE_MONEY_SCALE, RoundingMode.HALF_EVEN)

data class ReserveLineDto(
    val label: String,
    val glAccountCode: String?,
    val amount: BigDecimal,
    val reserveClass: String,
)

data class ReserveBaseDto(
    val currency: String,
    val lines: List<ReserveLineDto>,
    /** Null (with [requirementNotStated]) while a LIABILITY in this currency is unclassified. */
    val base: BigDecimal?,
    val rate: BigDecimal,
    val requirement: BigDecimal?,
    val requirementNotStated: String?,
)

data class ExcludedReserveBalanceDto(val glAccountCode: String?, val amount: BigDecimal, val reserveClass: String)

data class ReserveMappingDto(val key: String, val reserveClass: String, val description: String)

data class MinReservesAssumptionsDto(
    val parameterSetId: String,
    val parameterSetVersion: String,
    val source: String,
    val rate: BigDecimal,
    val remunerationRate: BigDecimal,
    val holdingCurrency: String,
    val glAccounts: List<ReserveMappingDto>,
    val glAccountTypes: List<ReserveMappingDto>,
)

data class MinReservesResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currencies: List<ReserveBaseDto>,
    val holdingCurrency: String,
    /** Null (with [holdingsNotStated]) when no GL account is mapped as the ČNB current account. */
    val holdings: List<ReserveLineDto>?,
    val totalHoldings: BigDecimal?,
    val holdingsNotStated: String?,
    /** Requirement on the holding-currency book; null when that book has an unclassified liability. */
    val requirement: BigDecimal?,
    /** Holdings − requirement; negative is a shortfall. Null when [requirement] is. */
    val surplus: BigDecimal?,
    val remunerationRate: BigDecimal,
    val remuneration: BigDecimal,
    val excluded: List<ExcludedReserveBalanceDto>,
    val unclassified: List<UnclassifiedBalanceDto>,
    val notes: List<String>,
    val assumptions: MinReservesAssumptionsDto,
)

private fun ReserveLine.toDto() = ReserveLineDto(label, glAccountCode, amount.reserveMoney(), reserveClass.wire)

private fun ReserveClass.mapping(key: String) = ReserveMappingDto(key, wire, description)

fun MinReservesAnalysis.toResponse(): MinReservesResponse = MinReservesResponse(
    runId = run.id,
    asOf = run.asOf.toString(),
    provenance = run.provenance.wire,
    parameterSetId = parameters.id,
    parameterSetVersion = parameters.version,
    currencies = result.currencies.map {
        ReserveBaseDto(
            it.currency,
            it.lines.map { l ->
                l.toDto()
            },
            it.base?.reserveMoney(),
            it.rate,
            it.requirement?.reserveMoney(),
            it.requirementNotStated,
        )
    },
    holdingCurrency = result.holdingCurrency,
    holdings = result.holdings?.map { it.toDto() },
    totalHoldings = result.totalHoldings?.reserveMoney(),
    holdingsNotStated = result.holdingsNotStated,
    requirement = result.requirement?.reserveMoney(),
    surplus = result.surplus?.reserveMoney(),
    remunerationRate = result.remunerationRate,
    remuneration = result.remuneration.reserveMoney(),
    excluded = result.excluded.map {
        ExcludedReserveBalanceDto(it.glAccountCode, it.amount.reserveMoney(), it.reserveClass.wire)
    },
    unclassified = result.unclassified.map {
        UnclassifiedBalanceDto(it.glAccountCode, it.glAccountType, it.currency, it.amount.reserveMoney(), it.reason)
    },
    notes = result.notes,
    assumptions = MinReservesAssumptionsDto(
        parameterSetId = parameters.id,
        parameterSetVersion = parameters.version,
        source = parameters.source,
        rate = parameters.rate,
        remunerationRate = parameters.remunerationRate,
        holdingCurrency = parameters.holdingCurrency,
        glAccounts = parameters.glAccounts.toSortedMap().map { (k, v) -> v.mapping(k) },
        glAccountTypes = parameters.glAccountTypes.toSortedMap().map { (k, v) -> v.mapping(k) },
    ),
)
