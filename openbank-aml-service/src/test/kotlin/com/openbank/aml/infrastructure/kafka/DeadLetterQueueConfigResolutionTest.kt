// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.kafka

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The DLQ wiring is a GUARD, so it is proven by what it prevents, not by what the file says.
 *
 * #5698 converted ~21 consumers from swallow-and-ack to rethrow "so the connector dead-letters".
 * SmallRye's DEFAULT `failure-strategy` is `fail`, which STOPS the channel — so the rethrow is only
 * an improvement where a DLQ is actually configured (#5745). This test asserts the two properties
 * the Kafka connector reads are RESOLVABLE, through the very class Quarkus uses to read
 * `application.yaml` — not that the file contains a line.
 *
 * The distinction is not academic. `KafkaConnectorIncomingConfiguration` calls
 * `getOptionalValue("dead-letter-queue.topic", …)` on the plain, unquoted property name, and
 * SmallRye Config's YAML source unconditionally QUOTES any leaf map key containing a literal dot.
 * So the natural-looking one-liner
 *
 *     dead-letter-queue.topic: openbank.dlq.aml.party-events-in
 *
 * registers as `…party-events-in."dead-letter-queue.topic"`, quotes included, which the connector
 * never finds — nothing errors, and the DLQ silently falls back to its implicit default name
 * (`dead-letter-topic-<channel>`, which collides across the eight services that share the
 * `party-events-in` channel name). Issue #686 is the same footgun for `group.id`.
 *
 * `dlqTopicWrittenAsADottedLeafKeyIsUnreadable` is the negative control: it feeds this test's own
 * mechanism the spelling that must NOT work and shows it does not. Without it, the positive
 * assertion below could not distinguish a working config from an inert one.
 */
class DeadLetterQueueConfigResolutionTest {

    private val channel = "mp.messaging.incoming.party-events-in"

    private fun source(name: String, yaml: String) = YamlConfigSource(name, yaml)

    private fun serviceConfig() = source("application.yaml", File("src/main/resources/application.yaml").readText())

    @Test
    fun `the party-events-in channel resolves a dead-letter-queue strategy and an explicit topic`() {
        val cfg = serviceConfig()

        assertThat(cfg.getValue("$channel.failure-strategy"))
            .describedAs("without this the connector defaults to `fail` and a rethrow WEDGES the channel")
            .isEqualTo("dead-letter-queue")

        assertThat(cfg.getValue("$channel.dead-letter-queue.topic"))
            .describedAs("the exact property name KafkaConnectorIncomingConfiguration reads")
            .isEqualTo("openbank.dlq.aml.party-events-in")
    }

    @Test
    fun `the topic is named explicitly, never left to SmallRye's channel-derived default`() {
        // `party-events-in` is declared by eight services. SmallRye's implicit
        // `dead-letter-topic-party-events-in` is derived from the channel name ALONE, so all eight
        // would dead-letter into one topic and the AccountPartyEventDeadLettered alert would
        // misattribute every record in it.
        assertThat(serviceConfig().getValue("$channel.dead-letter-queue.topic"))
            .startsWith("openbank.dlq.aml.")
    }

    @Test
    fun `the service config does not use the unreadable dotted-leaf spelling`() {
        assertThat(serviceConfig().propertyNames)
            .doesNotContain("""$channel."dead-letter-queue.topic"""")
    }

    @Test
    fun `dlq topic written as a dotted leaf key is unreadable — negative control`() {
        val dotted = source(
            "dotted",
            """
            mp:
              messaging:
                incoming:
                  party-events-in:
                    failure-strategy: dead-letter-queue
                    dead-letter-queue.topic: openbank.dlq.aml.party-events-in
            """.trimIndent(),
        )

        // What the connector asks for: absent. Nothing errors; the default silently applies.
        assertThat(dotted.getValue("$channel.dead-letter-queue.topic")).isNull()
        // Where the value actually went.
        assertThat(dotted.propertyNames).contains("""$channel."dead-letter-queue.topic"""")

        // The NESTED spelling this service uses produces the property the connector reads.
        val nested = source(
            "nested",
            """
            mp:
              messaging:
                incoming:
                  party-events-in:
                    failure-strategy: dead-letter-queue
                    dead-letter-queue:
                      topic: openbank.dlq.aml.party-events-in
            """.trimIndent(),
        )
        assertThat(nested.getValue("$channel.dead-letter-queue.topic"))
            .isEqualTo("openbank.dlq.aml.party-events-in")
        assertThat(nested.propertyNames)
            .doesNotContain("""$channel."dead-letter-queue.topic"""")
    }
}
