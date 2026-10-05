// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.aml.infrastructure.kafka

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Proves the YAML source behavior behind the fleet ratchet (#11683). */
class DottedMessagingKeyResolutionTest {
    @Test
    fun `every observed dotted Kafka key family is unreadable under its plain connector property`() {
        val cases = mapOf(
            "incoming" to listOf(
                "group.id",
                "auto.offset.reset",
                "client.id",
                "value.deserializer",
                "key.deserializer",
            ),
            "outgoing" to listOf(
                "client.id",
                "value.serializer",
                "key.serializer",
                "bootstrap.servers",
            ),
        )

        cases.forEach { (direction, keys) ->
            keys.forEach { key ->
                val prefix = "mp.messaging.$direction.probe"
                val dotted = source(direction, key, "probe-value")
                assertThat(dotted.getValue("$prefix.$key"))
                    .describedAs("$direction $key dotted leaf must not resolve as the connector's plain property")
                    .isNull()
                assertThat(dotted.propertyNames)
                    .contains("""$prefix."$key"""")

                val nested = source(direction, key, "probe-value", nested = true)
                assertThat(nested.getValue("$prefix.$key"))
                    .describedAs("$direction $key nested spelling must resolve")
                    .isEqualTo("probe-value")
            }
        }
    }

    private fun source(direction: String, key: String, value: String, nested: Boolean = false): YamlConfigSource {
        val property =
            if (nested) {
                key.split('.').mapIndexed { index, part ->
                    "${"  ".repeat(index)}$part:" + if (index == key.count { it == '.' }) " $value" else ""
                }.joinToString("\n        ")
            } else {
                "$key: $value"
            }
        val yaml = "mp:\n  messaging:\n    $direction:\n      probe:\n        $property\n"
        return YamlConfigSource("probe-$direction-$key", yaml)
    }
}
