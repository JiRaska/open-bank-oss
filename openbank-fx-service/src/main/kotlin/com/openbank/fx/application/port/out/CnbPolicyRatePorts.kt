// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.fx.domain.cnb.CnbPolicyRateObservation
import com.openbank.fx.domain.cnb.CnbPolicyRateUpsert
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.time.LocalDate

/** One downloaded history file: the raw bytes (hashed for provenance) and the URL they came from. */
class CnbPolicyRateDocument(val sourceUrl: String, val body: ByteArray)

/** Outbound port: the ČNB policy-rate history file of a feed-backed instrument. */
interface CnbPolicyRateFeed {
    suspend fun fetch(instrument: CnbPolicyInstrument): CnbPolicyRateDocument

    /** The ČNB minimum-reserve history workbook (`PMR_historie_zmen.xlsx`), raw bytes. */
    suspend fun fetchMinimumReserves(): CnbPolicyRateDocument
}

/** Where one batch of observations came from. */
data class CnbPolicyRateProvenance(val sourceUrl: String, val fetchedAt: Instant, val contentSha256: String)

/** A rate that changed for a date already stored — the ČNB does not revise history, so it is surfaced. */
data class CnbPolicyRateRevision(
    val instrument: CnbPolicyInstrument,
    val effectiveFrom: LocalDate,
    val previousRate: java.math.BigDecimal,
    val rate: java.math.BigDecimal,
)

data class CnbPolicyRateUpsertOutcome(
    val counts: CnbPolicyRateUpsert,
    val revisions: List<CnbPolicyRateRevision>,
    val published: Int,
)

interface CnbPolicyRateRepository {
    /**
     * In ONE transaction: inserts each observation absent for (instrument, effectiveFrom), leaves an
     * identical one alone, updates a differing one (keeping the previous rate), then writes one
     * outbox row — built by [event] — for every row of [instrument] not yet published, and marks
     * those rows published. Rows and their events therefore commit or roll back together.
     */
    suspend fun upsertAndPublish(
        instrument: CnbPolicyInstrument,
        observations: List<CnbPolicyRateObservation>,
        provenance: CnbPolicyRateProvenance,
        event: (CnbPolicyRateFact) -> OutboxMessage,
    ): CnbPolicyRateUpsertOutcome

    /** The row with the latest effectiveFrom <= [asOf], or null. */
    suspend fun findEffective(instrument: CnbPolicyInstrument, asOf: LocalDate): CnbPolicyRateFact?
}
