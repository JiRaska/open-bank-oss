// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.kafka

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The DLQ wiring is a GUARD, so it is proven by what it prevents, not by what the file says.
 *
 * Both incoming channels here dead-letter into an EXPLICIT, service-scoped topic. Until #5752 they
 * did not: `failure-strategy: dead-letter-queue` was set and the topic was left implicit, so
 * SmallRye derived `dead-letter-topic-<channel>` from the channel name ALONE. That name is not
 * unique to a service — `party-events-in` is declared by eight services here, and
 * `delegation-events-in` by two (this one and card-issuance-service), which were therefore
 * dead-lettering into a single shared topic that no alert could attribute.
 *
 * The comment that used to sit next to `party-events-in` asserted an explicit topic was IMPOSSIBLE
 * in `application.yaml`, on the grounds that `dead-letter-queue.topic` is a dotted leaf key which
 * SmallRye Config's YAML source quotes. True premise, wrong conclusion, and it suppressed the fix:
 * only the DOTTED spelling is inert. `KafkaConnectorIncomingConfiguration` calls
 * `getOptionalValue("dead-letter-queue.topic", …)` on the plain, unquoted property name, so
 *
 *     dead-letter-queue.topic: openbank.dlq.account.party-events-in    // registers QUOTED, inert
 *     dead-letter-queue:                                               // resolves
 *       topic: openbank.dlq.account.party-events-in
 *
 * differ. Nothing errors in the first case; the implicit default silently applies. Issue #686 is
 * the same footgun for `group.id`.
 *
 * These assertions go through the very class Quarkus uses to read `application.yaml`, never a
 * string search for a line, and `dlqTopicWrittenAsADottedLeafKeyIsUnreadable` is the negative
 * control: it feeds this test's own mechanism the spelling that must NOT work and shows it does
 * not. Without it, the positive assertions could not distinguish a working config from an inert one.
 */
class DeadLetterQueueConfigResolutionTest {

    private fun serviceConfig() =
        YamlConfigSource("application.yaml", File("src/main/resources/application.yaml").readText())

    private fun channel(name: String) = "mp.messaging.incoming.$name"

    @Test
    fun `both incoming channels resolve a dead-letter-queue strategy and an explicit topic`() {
        val cfg = serviceConfig()

        for ((name, topic) in EXPECTED) {
            assertThat(cfg.getValue("${channel(name)}.failure-strategy"))
                .describedAs("$name: without this the connector defaults to `fail` and a rethrow WEDGES the channel")
                .isEqualTo("dead-letter-queue")

            assertThat(cfg.getValue("${channel(name)}.dead-letter-queue.topic"))
                .describedAs("$name: the exact property name KafkaConnectorIncomingConfiguration reads")
                .isEqualTo(topic)
        }
    }

    @Test
    fun `the topics are named explicitly, never left to SmallRye's channel-derived default`() {
        val cfg = serviceConfig()
        for ((name, _) in EXPECTED) {
            val resolved = cfg.getValue("${channel(name)}.dead-letter-queue.topic")
            assertThat(resolved)
                .describedAs("$name: `dead-letter-topic-$name` is derived from the channel name alone and is shared")
                .isNotEqualTo("dead-letter-topic-$name")
            assertThat(resolved).startsWith("openbank.dlq.account.")
        }
    }

    @Test
    fun `the service config does not use the unreadable dotted-leaf spelling`() {
        val names = serviceConfig().propertyNames
        for ((name, _) in EXPECTED) {
            assertThat(names).doesNotContain("""${channel(name)}."dead-letter-queue.topic"""")
        }
    }

    @Test
    fun `dlq topic written as a dotted leaf key is unreadable — negative control`() {
        val c = channel("party-events-in")

        val dotted = YamlConfigSource(
            "dotted",
            """
            mp:
              messaging:
                incoming:
                  party-events-in:
                    failure-strategy: dead-letter-queue
                    dead-letter-queue.topic: openbank.dlq.account.party-events-in
            """.trimIndent(),
        )
        // What the connector asks for: absent. Nothing errors; the implicit default silently applies.
        assertThat(dotted.getValue("$c.dead-letter-queue.topic")).isNull()
        // Where the value actually went.
        assertThat(dotted.propertyNames).contains("""$c."dead-letter-queue.topic"""")

        // The NESTED spelling this service now uses produces the property the connector reads.
        val nested = YamlConfigSource(
            "nested",
            """
            mp:
              messaging:
                incoming:
                  party-events-in:
                    failure-strategy: dead-letter-queue
                    dead-letter-queue:
                      topic: openbank.dlq.account.party-events-in
            """.trimIndent(),
        )
        assertThat(nested.getValue("$c.dead-letter-queue.topic"))
            .isEqualTo("openbank.dlq.account.party-events-in")
        assertThat(nested.propertyNames).doesNotContain("""$c."dead-letter-queue.topic"""")
    }

    private companion object {
        val EXPECTED = listOf(
            "party-events-in" to "openbank.dlq.account.party-events-in",
            "delegation-events-in" to "openbank.dlq.account.delegation-events-in",
        )
    }
}
