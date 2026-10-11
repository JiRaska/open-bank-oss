// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.messaging

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The consumer group and replay policy ship in the image's application.yaml, NESTED. The dotted
 * one-liner (`group.id: x`) registers as the quoted key `…."group.id"` and never reaches the Kafka
 * connector (#686), so this reads the real file through the real YAML source and asserts the
 * UNQUOTED property the connector looks up. The group must equal the Read ACL in
 * kafka-pension-mtls.yaml, or the consumer is refused by the broker.
 */
class DomesticPaymentConsumerConfigTest {
    private val source =
        YamlConfigSource(
            requireNotNull(javaClass.classLoader.getResource("application.yaml")) { "application.yaml on classpath" },
        )

    @Test
    fun `consumer group and offset reset resolve as connector-visible keys`() {
        val prefix = "mp.messaging.incoming.domestic-payment-events-in."
        assertThat(source.getValue(prefix + "group.id")).isEqualTo("pension-service-domestic-payment")
        assertThat(source.getValue(prefix + "auto.offset.reset")).isEqualTo("latest")
        assertThat(source.getValue(prefix + "connector")).isEqualTo("smallrye-kafka")
    }
}
