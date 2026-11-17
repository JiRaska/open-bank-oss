// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The monitoring consumers rethrow, so their dead-letter wiring is a guard. Asserted the way
 * [DeadLetterQueueConfigResolutionTest] does it — through the class Quarkus reads the file with —
 * and that test holds the negative control for the unreadable dotted spelling.
 */
class MonitoringDeadLetterQueueConfigTest {
    private val config = YamlConfigSource("application.yaml", File("src/main/resources/application.yaml").readText())

    @ParameterizedTest
    @ValueSource(strings = ["ledger-events-in", "transaction-events-in", "payment-held-in"])
    fun `each monitoring channel resolves a dead-letter strategy and its own explicit topic`(channel: String) {
        val prefix = "mp.messaging.incoming.$channel"
        assertThat(config.getValue("$prefix.failure-strategy")).isEqualTo("dead-letter-queue")
        assertThat(config.getValue("$prefix.dead-letter-queue.topic")).isEqualTo("openbank.dlq.aml.$channel")
        assertThat(config.propertyNames).doesNotContain("""$prefix."dead-letter-queue.topic"""")
        // Without it the dead-letter producer JSON-encodes the String payload (measured: a parked
        // record came back wrapped in quotes), so a replay would not deliver what was consumed.
        assertThat(config.getValue("$prefix.dead-letter-queue.value.serializer")).endsWith("StringSerializer")
        assertThat(config.getValue("$prefix.value.deserializer")).endsWith("StringDeserializer")
        assertThat(config.getValue("$prefix.key.deserializer")).endsWith("StringDeserializer")
        assertThat(config.getValue("$prefix.group.id")).isEqualTo("openbank-aml-service")
        assertThat(config.propertyNames.filter { it.startsWith(prefix) && it.contains('"') })
            .describedAs("no dotted leaf key on this channel: the connector cannot read one")
            .isEmpty()
    }
}
