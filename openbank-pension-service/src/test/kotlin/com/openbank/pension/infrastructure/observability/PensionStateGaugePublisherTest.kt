// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PensionStateGaugePublisherTest {
    private val registry = SimpleMeterRegistry()
    private val publisher = PensionStateGaugePublisher(registry)

    private fun contractRow(count: Long) = StateRow(
        labels = mapOf("status" to "ACTIVE", "product_line" to "DPS", "jurisdiction" to "CZ"),
        count = count,
    )

    @Test
    fun `cold pod has absent business state until first successful snapshot`() {
        assertThat(registry.find("openbank.pension.contracts").gauges()).isEmpty()
        assertThat(registry.find("openbank.pension.queue.size").gauges()).isEmpty()
        assertThat(registry.find("openbank.pension.queue.oldest_age_seconds").gauges()).isEmpty()

        publisher.publish(
            PensionStateSnapshot(
                contracts = listOf(contractRow(2)),
                aggregates = emptyList(),
                queueOldestAgeSeconds = mapOf("unmatched_payments" to 0.0),
                queueSize = mapOf("unmatched_payments" to 0),
            ),
        )

        assertThat(registry.find("openbank.pension.contracts").tag("status", "ACTIVE").gauge()?.value()).isEqualTo(2.0)
        assertThat(registry.find("openbank.pension.queue.size").tag("queue", "unmatched_payments").gauge()?.value())
            .isEqualTo(0.0)
        assertThat(
            registry.find("openbank.pension.queue.oldest_age_seconds")
                .tag("queue", "unmatched_payments").gauge()?.value(),
        )
            .isEqualTo(0.0)
    }

    @Test
    fun `a status row that disappears does not persist as a stale gauge`() {
        publisher.publish(
            PensionStateSnapshot(
                contracts = listOf(contractRow(1)),
                aggregates = emptyList(),
                queueOldestAgeSeconds = emptyMap(),
                queueSize = emptyMap(),
            ),
        )
        publisher.publish(PensionStateSnapshot(emptyList(), emptyList(), emptyMap(), emptyMap()))

        assertThat(registry.find("openbank.pension.contracts").gauges()).isEmpty()
    }
}
