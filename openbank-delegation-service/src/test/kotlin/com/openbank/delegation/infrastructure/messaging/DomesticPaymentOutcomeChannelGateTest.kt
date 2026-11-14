// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The consumer is OFF unless a deployment turns it on, and "off" means the CHANNEL does not
 * exist — not that a running consumer ignores what it reads — so the ACLs and the DLQ topic can
 * land in any order relative to the image.
 */
class DomesticPaymentOutcomeChannelGateTest {

    @Suppress("UNCHECKED_CAST")
    private val config: Map<String, Any?> = javaClass.classLoader.getResourceAsStream("application.yaml")!!
        .use { Yaml().load<Map<String, Any?>>(it) }

    @Suppress("UNCHECKED_CAST")
    private fun path(vararg keys: String): Any? = keys.fold<String, Any?>(config) { node, key ->
        (node as? Map<String, Any?>)?.get(key)
    }

    @Test
    fun `the channel's enabled flag is bound to the consumer switch`() {
        assertThat(
            path("mp", "messaging", "incoming", DomesticPaymentOutcomeConsumer.CHANNEL, "enabled"),
        ).isEqualTo("\${openbank.delegation.payment-outcome-consumer.enabled}")
    }

    @Test
    fun `the switch defaults to off`() {
        assertThat(path("openbank", "delegation", "payment-outcome-consumer", "enabled"))
            .isEqualTo("\${OPENBANK_DELEGATION_PAYMENT_OUTCOME_CONSUMER_ENABLED:false}")
    }

    @Test
    fun `the channel dead-letters into its explicit per-service topic`() {
        val channel = path("mp", "messaging", "incoming", DomesticPaymentOutcomeConsumer.CHANNEL) as Map<*, *>
        assertThat(channel["failure-strategy"]).isEqualTo("dead-letter-queue")
        assertThat((channel["dead-letter-queue"] as Map<*, *>)["topic"])
            .isEqualTo("openbank.dlq.delegation.domestic-payment-events-in")
        assertThat(channel["topic"]).isEqualTo("openbank.domestic.payment.events")
    }
}
