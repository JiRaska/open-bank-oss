// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import com.openbank.fx.domain.event.FxEvent
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
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

    suspend fun findById(id: UUID): FxConversion?

    suspend fun findByIdempotencyKey(key: String): FxConversion?

    suspend fun findByPartyId(partyId: UUID): List<FxConversion>
}

/** Outbound port for publishing FX domain events to the broker. */
interface FxEventPublisher {

    suspend fun publish(event: FxEvent)
}
