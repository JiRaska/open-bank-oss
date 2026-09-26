// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.event

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

sealed class FxEvent {
    abstract val occurredAt: Instant

    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` (audit-service) as the
     * strongest (EVENT-sourced) attribution — issue #3994/#5256. `EventAttribution.TopicAttribution`
     * already maps `openbank.fx.conversion.completed` -> `fx-service` correctly, but only as
     * TOPIC-sourced, not the producer's own claim, and audit-service subscribes to this topic
     * today (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics
     * list), so this is a live attribution upgrade. `FxConversionExecuted` (the only subtype
     * actually reaching the outbox today, via `FxService.settle`) is serialised with
     * `objectMapper.writeValueAsString`, so the wire key exists only as this Kotlin property name.
     */
    val sourceService: String = SOURCE_SERVICE

    companion object {
        internal const val SOURCE_SERVICE = "fx-service"
    }
}
data class FxRatePublished(
    val rateId: UUID,
    val pair: String,
    val midRate: BigDecimal,
    override val occurredAt: Instant,
) : FxEvent()

/**
 * A central-bank fixing was ingested (ADR-0314 D5). One event per ingestion run that stored at
 * least one new rate, carrying every rate that run stored, so a consumer never has to reassemble
 * a fixing from per-currency messages. `rates` holds only the NEWLY stored rows: a re-run that
 * finds the day already present emits nothing, which is what makes the event as idempotent as
 * the ingestion itself.
 */
data class FxFixingPublished(
    val source: String,
    val fixingDate: LocalDate,
    val sequence: Int?,
    val quoteCurrency: String,
    val validFrom: Instant,
    val validTo: Instant,
    val rates: List<FixingRate>,
    override val occurredAt: Instant,
) : FxEvent() {
    /** One stored rate: [ratePerUnit] CZK (the quote currency) for one unit of [currency]. */
    data class FixingRate(val rateId: UUID, val currency: String, val ratePerUnit: BigDecimal)

    companion object {
        const val EVENT_TYPE = "fx.fixing.published.v1"
    }
}
data class FxConversionExecuted(
    val conversionId: UUID,
    val partyId: UUID,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmount: Long,
    val toAmount: Long,
    val rate: BigDecimal,
    override val occurredAt: Instant,
) : FxEvent() {
    companion object {
        const val EVENT_TYPE = "fx.conversion.executed.v1"
    }
}
