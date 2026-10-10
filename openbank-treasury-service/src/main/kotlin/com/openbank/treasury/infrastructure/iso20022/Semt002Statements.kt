// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.iso20022

import com.openbank.libs.iso20022.SecuritiesHolding
import com.openbank.libs.iso20022.Semt002Reader
import com.openbank.treasury.domain.model.CustodyHolding
import com.openbank.treasury.domain.model.CustodyStatement
import java.math.BigDecimal

/**
 * A custodian's semt.002 (read by openbank-libs-iso20022's [Semt002Reader]) held to what the
 * portfolio store can represent EXACTLY — the same rule as [Camt053Values]: a figure the store
 * would round is refused, never silently changed. Every failure is an IllegalArgumentException (400).
 */
object Semt002Statements {
    private val reader = Semt002Reader()

    fun parse(xml: ByteArray): CustodyStatement {
        val s = reader.read(xml)
        return CustodyStatement(
            statementId = s.statementId,
            safekeepingAccount = s.safekeepingAccount,
            statementDate = s.statementDate,
            holdings = s.holdings.map(::holding),
        )
    }

    private fun holding(h: SecuritiesHolding): CustodyHolding {
        val value = requireNotNull(h.holdingValue) { "ISIN ${h.isin} states no AcctBaseCcyAmts/HldgVal valuation" }
        val currency = requireNotNull(h.holdingValueCurrency) { "ISIN ${h.isin} valuation states no currency" }
        return CustodyHolding(
            isin = h.isin,
            cfi = h.cfi,
            quantity = exact(h.quantity, QUANTITY_SCALE, QUANTITY_INTEGER_DIGITS, "ISIN ${h.isin} quantity"),
            valuation = exact(value, VALUE_SCALE, VALUE_INTEGER_DIGITS, "ISIN ${h.isin} valuation"),
            valuationCurrency = currency,
        )
    }

    private fun exact(value: BigDecimal, scale: Int, integerDigits: Int, what: String): BigDecimal {
        val v = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        require(v.scale() <= scale) { "$what has more than $scale decimal places: $value" }
        require(v.precision() - v.scale() <= integerDigits) {
            "$what has more than $integerDigits integer digits: $value"
        }
        return value
    }

    /** NUMERIC(24, 6) */
    private const val QUANTITY_SCALE = 6
    private const val QUANTITY_INTEGER_DIGITS = 18

    /** NUMERIC(19, 4) */
    private const val VALUE_SCALE = 4
    private const val VALUE_INTEGER_DIGITS = 15
}
