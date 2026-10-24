// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.fx.domain.model.FxConversion
import java.util.UUID

/**
 * The settlement posting pair behind one FX conversion; production persists no such record (the
 * booking is a transaction-service journal pair keyed by the conversion), so the scenario records
 * the money-side view here — the invariant-side correspondence between a conversion's terminal
 * status and the CZK + foreign-currency journals that did or did not move its funds.
 */
data class FxSettlementPosting(
    val conversionId: UUID,
    val czkJournalId: UUID?,
    val foreignJournalId: UUID?,
    val czkReversed: Boolean = false,
    val foreignReversed: Boolean = false,
)

/**
 * Money-path FX adoption: the invariant-side book for
 * [com.openbank.simulation.scenario.FxConversionScenario], mirroring SwiftMessageBook's role.
 * Holds the REAL `FxConversion` aggregates (fx-service) the scenario prices with the real
 * `CnbFixingRate.ratePerUnit` normalisation, `FxRate.inverted()` spread-side logic and
 * `FxConversionMath` margin arithmetic, plus the per-conversion journal pair the money-side
 * invariants assert over (one live CZK + foreign journal per settled conversion, none for a
 * failed one, both reversed for a conflicted one).
 */
class FxConversionBook {

    val conversions = mutableListOf<FxConversion>()
    private val postings = mutableMapOf<UUID, FxSettlementPosting>()

    fun recordConversion(conversion: FxConversion) {
        conversions.add(conversion)
    }

    fun replaceConversion(updated: FxConversion) {
        val index = conversions.indexOfFirst { it.id == updated.id }
        conversions[index] = updated
    }

    fun recordPosting(posting: FxSettlementPosting) {
        postings[posting.conversionId] = posting
    }

    fun replacePosting(updated: FxSettlementPosting) {
        postings[updated.conversionId] = updated
    }

    fun postingOf(conversionId: UUID): FxSettlementPosting? = postings[conversionId]
}
