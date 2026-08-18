// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Serialization round-trip for fx-service's outbox events (issue #3994/#5256, fleet follow-up
 * to #5255's domestic-payment fix and the twelve prior slices: #5267, #5329, #5336, #5337,
 * #5343, #5344, #5349, #5351, #5369, #5374, #5376, #5382).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer.resolveSourceService`
 * reads. Before this field, every audit row for `openbank.fx.conversion.completed` fell back to
 * `EventAttribution.TopicAttribution`'s TOPIC-sourced entry — correct, but not the producer's own
 * claim. Audit-service subscribes to that topic today
 * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
 * is a live attribution upgrade, not a forward-looking one. fx-service is a money-path service
 * (`rules.yaml: money_path_services`).
 *
 * `FxConversionExecuted` is the only subtype that reaches the outbox today
 * (`FxService.settle` -> `objectMapper.writeValueAsString(event)`); `FxRatePublished` is not
 * constructed anywhere in `src/main` (dead), so its round-trip is asserted only at the domain
 * level, never through a real publish path.
 */
class FxEventsTest {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val now = Instant.parse("2026-08-17T10:00:00Z")

    @Test
    fun `FxConversionExecuted carries sourceService on the wire`() {
        val event = FxConversionExecuted(
            conversionId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            fromCurrency = "EUR",
            toCurrency = "CZK",
            fromAmount = 10000,
            toAmount = 245000,
            rate = BigDecimal("24.50"),
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("fx-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("sourceService").asText()).isEqualTo("fx-service")
        assertThat(node.get("conversionId").asText()).isEqualTo(event.conversionId.toString())
    }

    @Test
    fun `FxRatePublished carries sourceService`() {
        val event = FxRatePublished(
            rateId = UUID.randomUUID(),
            pair = "EUR_CZK",
            midRate = BigDecimal("24.50"),
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("fx-service")
    }
}
