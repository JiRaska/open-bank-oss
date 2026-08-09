// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** Outbound persistence port for quoted FX rates (per base/quote/rate-type). */
interface FxRateRepository {

    suspend fun save(rate: FxRate): FxRate

    suspend fun findLatest(base: String, quote: String, type: RateType): FxRate?

    suspend fun findAll(): List<FxRate>

    /** Latest still-valid rate from a specific [source] (e.g. the ČNB central-bank fixing). */
    suspend fun findLatestBySource(base: String, quote: String, source: RateSource): FxRate?

    /**
     * The fixing from [source] that was **in effect at [at]** for this pair, or `null` if none was.
     *
     * "In effect" is the stored validity window, evaluated at [at] instead of at wall-clock now:
     * `validFrom <= at < validTo`, newest `validFrom` first. [findLatestBySource] answers the same
     * question with `at` pinned to `Instant.now()`, which is why a belated or manual revaluation of
     * an older business day marked that day at **today's** fixing (#3921 item 3) — the seam simply
     * had no way to ask for any other day.
     *
     * Two properties this deliberately keeps rather than relaxing:
     *
     *  - **The `validTo` bound stays.** Dropping it would make a dead feed resolve to the last
     *    fixing it ever published instead of to `null`, turning ledger's loud "skipping its
     *    revaluation leg" into a silent mark at an arbitrarily old rate. The three-day window is
     *    what makes a stale feed *absent* rather than *wrong*.
     *  - **`validFrom <= at` is new and is a tightening.** [findLatestBySource] filters only on
     *    `validTo`, so a future-dated fixing already in the table would be picked to value today.
     *    It cannot value a day it was not yet valid for.
     *
     * With `at` = the start of today, this returns exactly what [findLatestBySource] returns for
     * every row this service writes: `CnbRateIngestionService` sets both bounds to Prague midnights,
     * so no row can expire between the start of the day and now. `FxRateRepositoryAsOfTest` pins
     * that equivalence.
     */
    suspend fun findBySourceAsOf(base: String, quote: String, source: RateSource, at: Instant): FxRate?

    /**
     * Idempotency probe for source ingestion: the rate already stored for this [source]/pair whose
     * [validFrom] equals the fixing's business day start, if any. Used to make daily ČNB upserts a no-op.
     */
    suspend fun findBySourceAndValidFrom(base: String, quote: String, source: RateSource, validFrom: Instant): FxRate?

    /**
     * Historical rates for a pair, newest first. No validTo filter — returns all records including
     * expired ones. [from]/[to] bound validFrom; omit for an unbounded window.
     * [source] restricts to a single ingestion source (e.g. INTERNAL for bank commercial rates).
     */
    suspend fun findHistory(
        base: String,
        quote: String,
        source: RateSource? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<FxRate>
}

/** Outbound persistence port for executed FX conversions. */
interface FxConversionRepository {

    suspend fun save(conv: FxConversion): FxConversion

    /** Persists the conversion and a transactional-outbox row in the same DB transaction (#1033). */
    suspend fun saveWithOutbox(conv: FxConversion, outboxMessage: OutboxMessage): FxConversion

    suspend fun findById(id: UUID): FxConversion?

    suspend fun findByIdempotencyKey(key: String): FxConversion?

    suspend fun findByPartyId(partyId: UUID): List<FxConversion>
}
