// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import java.math.BigDecimal
import java.time.Instant

/**
 * A ČNB fixing as the revaluation consumes it: the statutory CZK-per-unit [rate] **and** the moment
 * that fixing became valid.
 *
 * [validFrom] is the whole point of this type (#3921). The port used to return a bare [BigDecimal],
 * so no time at all crossed the fx→ledger seam — fx-service persists and serves `validFrom`, and
 * ledger threw it away on the wire. With no date on this side there was nowhere a staleness check
 * or a freshness metric could even be *written*: a Friday fixing marked a Monday position with no
 * warning, no metric and no alert, bounded only by fx-service's own date-blind three-day validity
 * window. Carrying the instant does not by itself reject anything — it makes the age observable,
 * which is the prerequisite for everything else in #3921.
 *
 * Nullable on purpose: an fx-service that does not send `validFrom` (an older deploy, or a stored
 * row without one) must degrade to "age unknown", never to "age zero". A silent zero would read as
 * a perfectly fresh fixing on the very dashboards this exists to populate.
 */
data class CnbFixing(val rate: BigDecimal, val validFrom: Instant?)

/**
 * Outbound port for reading the ČNB central-bank fixing from `openbank-fx-service` (FX rates are
 * that service's bounded context — ADR-0046). Returns the statutory CZK-per-unit rate used for the
 * daily mark-to-ČNB revaluation, together with the fixing's own validity start.
 */
interface CnbRateProvider {

    /** Latest ČNB fixing as CZK per 1 unit of [base] (against CZK), or `null` if none is published. */
    suspend fun cnbRate(base: String): CnbFixing?
}
