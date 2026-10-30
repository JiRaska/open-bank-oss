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

/**
 * The participant-notice producer must resolve to notification-service's request topic. With no
 * outgoing block SmallRye uses the CHANNEL name as the topic — a topic with no KafkaTopic and no
 * Write ACL — so every send fails once notifications are enabled. Read through the real YAML source
 * so a dotted (quoted, inert) key cannot pass.
 */
class ParticipantNotificationProducerConfigTest {
    private val source =
        YamlConfigSource(
            requireNotNull(javaClass.classLoader.getResource("application.yaml")) { "application.yaml on classpath" },
        )

    @Test
    fun `participant notices resolve to the notification request topic the Write ACL grants`() {
        val prefix = "mp.messaging.outgoing.participant-notifications-out."
        assertThat(source.getValue(prefix + "connector")).isEqualTo("smallrye-kafka")
        assertThat(source.getValue(prefix + "topic")).isEqualTo("openbank.notification.requests")
        assertThat(source.getValue(prefix + "value.serializer"))
            .isEqualTo("org.apache.kafka.common.serialization.StringSerializer")
        assertThat(source.getValue(prefix + "key.serializer"))
            .isEqualTo("org.apache.kafka.common.serialization.StringSerializer")
    }
}

/** client.id must be NESTED to reach the connector (#12586): the dotted one-liner is quoted and inert. */
class ConsumerClientIdConfigTest {
    @Test
    fun `the payout consumer's client id resolves as a connector-visible key`() {
        val source = YamlConfigSource(
            requireNotNull(javaClass.classLoader.getResource("application.yaml")) { "application.yaml on classpath" },
        )
        assertThat(source.getValue("mp.messaging.incoming.domestic-payment-events-in.client.id"))
            .isEqualTo("pension-service-domestic-payment-\${quarkus.uuid}")
    }
}
