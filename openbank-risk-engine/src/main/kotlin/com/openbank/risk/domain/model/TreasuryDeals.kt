// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One of the bank's own money-market deals as the risk engine knows it (ADR-0314 D4, ADR-0315 D6),
 * maintained from treasury-service's `treasury.deal.*` events. [rate] is the annual percentage
 * (4.25 = 4.25 %), null only when the booked event carrying it was never seen.
 */
data class TreasuryDeal(
    val dealId: UUID,
    val product: String,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal?,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val state: String,
) {
    val isAsset: Boolean get() = product != MM_BORROWING && product != CNB_LOMBARD

    /**
     * On the balance sheet at [asOf]: settled on or before it, and not yet matured at it. A deal
     * that has since MATURED still counts for an earlier [asOf] before its maturity date. A
     * REVERSED deal is left out: the reversal's own posting date is not in the events, so its
     * position at an earlier date cannot be stated rather than guessed.
     */
    fun onBookAt(asOf: LocalDate): Boolean = !valueDate.isAfter(asOf) &&
        when (state) {
            SETTLED -> true
            MATURED -> maturityDate.isAfter(asOf)
            else -> false
        }

    companion object {
        const val MM_PLACEMENT = "MM_PLACEMENT"
        const val MM_BORROWING = "MM_BORROWING"
        const val CNB_DEPOSIT_FACILITY = "CNB_DEPOSIT_FACILITY"

        /** Overnight borrowing from the ČNB lombard facility (#10896): a liability on 2320. */
        const val CNB_LOMBARD = "CNB_LOMBARD"
        const val BOOKED = "BOOKED"
        const val SETTLED = "SETTLED"
        const val MATURED = "MATURED"
        const val REVERSED = "REVERSED"
    }
}

/**
 * Maps a treasury deal to a contract-level [InstrumentKind.MONEY_MARKET_DEAL] instrument on the
 * principal GL account treasury posts it to (ledger V29, treasury `TreasuryChart`), in the
 * trial-balance convention: a placement or a ČNB deposit is +principal, a borrowing (interbank or
 * ČNB lombard, ledger V30 account 2320) −principal.
 * Accrued interest (1520/1521, 2310/2311) stays GL-level, as a loan's interest receivable does.
 */
object TreasuryInstrumentMapper {

    /** Every principal account a money-market deal lives on; carried at contract level when read. */
    val PRINCIPAL_CODES: Set<String> = setOf("1500", "1501", "1510", "2300", "2301", "2320")

    /**
     * Products this mapper can turn into a contract-level instrument. Anything else — e.g.
     * `FX_SPOT` (#10896), whose principal posts to GL-level FX position accounts 1990/1991/1001/1002
     * that stay GL-level rather than being carried as a contract-level position — is out of scope
     * for this engine and must never reach [glAccountCode] or [toInstrument].
     */
    val SUPPORTED_PRODUCTS: Set<String> =
        setOf(
            TreasuryDeal.MM_PLACEMENT,
            TreasuryDeal.MM_BORROWING,
            TreasuryDeal.CNB_DEPOSIT_FACILITY,
            TreasuryDeal.CNB_LOMBARD,
        )

    fun glAccountCode(product: String, currency: String): String = when (product to currency) {
        TreasuryDeal.MM_PLACEMENT to "CZK" -> "1500"
        TreasuryDeal.MM_PLACEMENT to "EUR" -> "1501"
        TreasuryDeal.CNB_DEPOSIT_FACILITY to "CZK" -> "1510"
        TreasuryDeal.MM_BORROWING to "CZK" -> "2300"
        TreasuryDeal.MM_BORROWING to "EUR" -> "2301"
        TreasuryDeal.CNB_LOMBARD to "CZK" -> "2320"
        else -> error("no treasury principal account for $product in $currency")
    }

    fun toInstrument(deal: TreasuryDeal): Instrument = Instrument(
        id = "treasury:${deal.dealId}",
        kind = InstrumentKind.MONEY_MARKET_DEAL,
        glAccountCode = glAccountCode(deal.product, deal.currency),
        currency = deal.currency,
        outstanding = if (deal.isAsset) deal.principal else deal.principal.negate(),
        valueDate = deal.valueDate,
        maturityDate = deal.maturityDate,
        rateTerms = deal.rate?.let { RateTerms(RateType.FIXED, it) },
        counterpartyRef = deal.counterpartyId,
        ifrs9Stage = null,
        extension = null,
    )

    /** ACT/360 interest over the deal's life, rounded half-up to 2 places as treasury rounds it. */
    fun interest(
        principal: BigDecimal,
        ratePercent: BigDecimal,
        valueDate: LocalDate,
        maturity: LocalDate,
    ): BigDecimal {
        val days = ChronoUnit.DAYS.between(valueDate, maturity)
        return principal.multiply(ratePercent).multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(DAYS_IN_YEAR * PERCENT), 2, RoundingMode.HALF_UP)
    }

    private const val DAYS_IN_YEAR = 360L
    private const val PERCENT = 100L
}
