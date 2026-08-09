// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.`in`

import com.openbank.fx.domain.model.FxRate
import java.time.LocalDate

/** Ingest the ČNB fixing for [date] (or the latest published one when `null`). */
data class IngestCnbFixingCommand(val date: LocalDate? = null)

/**
 * Outcome of a ČNB ingestion run: the fixing [date] and publication [sequence] parsed from the
 * feed, how many configured-currency rates were newly [ingested] vs. [skipped] (already present,
 * idempotent), and the [currencies] that were stored.
 */
data class CnbIngestionResult(
    val date: LocalDate,
    val sequence: Int?,
    val ingested: Int,
    val skipped: Int,
    val currencies: List<String>,
)

/** Inbound port for ingesting and reading the ČNB central-bank fixing as `source = CNB` FxRates. */
interface CnbRateIngestionUseCase {

    suspend fun ingest(cmd: IngestCnbFixingCommand): CnbIngestionResult

    /**
     * The ČNB fixing for `base`/`quote` (`source = CNB`) that was in effect **on [asOf]**, or the
     * latest still-valid one when [asOf] is `null`. `null` if there is none.
     *
     * [asOf] exists so a belated or manual revaluation of an older business day can be marked at
     * the fixing that was current *then* rather than at today's (#3921 item 3). Omitting it keeps
     * the live daily path byte-for-byte as it was; passing today's date resolves to the same row,
     * because every fixing this service writes has Prague-midnight validity bounds.
     */
    suspend fun getCnbRate(base: String, quote: String, asOf: LocalDate? = null): FxRate?
}
