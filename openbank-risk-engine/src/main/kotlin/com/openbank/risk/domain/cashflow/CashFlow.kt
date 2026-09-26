// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.libs.domain.money.CurrencyCode
import java.math.BigDecimal
import java.time.LocalDate

enum class CashFlowKind { PRINCIPAL, INTEREST }

/**
 * One dated, currency-tagged flow (ADR-0314 D6). [amount] is signed from the BANK's side:
 * money coming in is positive, money going out negative — so a loan asset projects positive
 * flows and a customer deposit, a liability, negative ones.
 *
 * A flow is DERIVED per run from a position, a curve set and a behavioural model; it is never
 * stored as the record, because any of those three changing changes it.
 */
data class CashFlow(val date: LocalDate, val currency: String, val kind: CashFlowKind, val amount: BigDecimal)

internal fun minorUnits(currency: String): Int = CurrencyCode.of(currency).defaultFractionDigits
