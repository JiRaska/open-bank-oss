// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.event

import java.math.BigDecimal
import java.time.Instant
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
data class FxConversionExecuted(
    val conversionId: UUID,
    val partyId: UUID,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmount: Long,
    val toAmount: Long,
    val rate: BigDecimal,
    override val occurredAt: Instant,
) : FxEvent()
