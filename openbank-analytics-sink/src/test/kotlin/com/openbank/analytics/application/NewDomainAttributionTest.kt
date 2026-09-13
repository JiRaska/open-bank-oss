// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The four domains ADR-0282 phase 1 adds: cards, lending, standing orders and FX (#8792).
 *
 * The property under test is not "these events get ingested" — the consumer is generic and would
 * ingest anything. It is that each domain resolves to ONE aggregate type whichever path attributes
 * it, and to its own identifier rather than a party's.
 */
class NewDomainAttributionTest {

    private val mapper = ObjectMapper()
    private val consumer = AnalyticsConsumer().apply {
        clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC)
        objectMapper = mapper
    }

    private fun envelope(json: String, topic: String, key: String? = null) =
        consumer.toEnvelope(mapper.readTree(json), EventAddress(topic = topic, key = key, ceType = null))

    /**
     * The split this guards against is real and has happened before (#4553): a card issuance carries
     * cardId, partyId AND accountId, so with the body checks left in their old order it resolved to
     * ACCOUNT, while CardStatusChanged — which carries only cardId — fell through to the topic and
     * resolved to CARD. One domain, two aggregate types, divided by which fields an event happens to
     * have, and `silver_current_state` groups by (aggregate_type, aggregate_id).
     */
    @Test
    fun `a card issuance is a CARD keyed by the card, not an ACCOUNT keyed by the account`() {
        val cardId = UUID.randomUUID()
        val env = envelope(
            """
            { "cardId": "$cardId", "partyId": "${UUID.randomUUID()}",
              "accountId": "${UUID.randomUUID()}", "eventType": "card.issued.v1",
              "sourceService": "card-issuance-service" }
            """.trimIndent(),
            "openbank.cards.events",
        )
        assertThat(env.aggregateType).isEqualTo("CARD")
        assertThat(env.aggregateId).isEqualTo(cardId.toString())
        assertThat(env.sourceService).isEqualTo("card-issuance-service")
    }

    @Test
    fun `a card status change resolves to the SAME aggregate type as an issuance`() {
        val cardId = UUID.randomUUID()
        val env = envelope(
            """{ "cardId": "$cardId", "eventType": "card.status_changed.v1" }""",
            "openbank.cards.events",
        )
        assertThat(env.aggregateType).isEqualTo("CARD")
        assertThat(env.aggregateId).isEqualTo(cardId.toString())
    }

    @Test
    fun `a standing order is its own aggregate, not the party's`() {
        val orderId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val env = envelope(
            """{ "orderId": "$orderId", "partyId": "$partyId", "eventType": "standing_order.due" }""",
            "openbank.standing-orders.order.event",
        )
        assertThat(env.aggregateType).isEqualTo("STANDING_ORDER")
        assertThat(env.aggregateId).isEqualTo(orderId.toString())
        // Every one of a party's standing orders carries the same partyId. Keying on it would
        // collapse them into a single aggregate and mix them with real party events.
        assertThat(env.aggregateId).isNotEqualTo(partyId.toString())
        assertThat(env.sourceService).isEqualTo("standing-order-service")
    }

    @Test
    fun `a loan event is a LENDING aggregate keyed by the loan`() {
        val loanId = UUID.randomUUID()
        val env = envelope(
            """{ "loanId": "$loanId", "partyId": "${UUID.randomUUID()}", "eventType": "loan.disbursed" }""",
            "openbank.lending.events",
        )
        assertThat(env.aggregateType).isEqualTo("LENDING")
        assertThat(env.aggregateId).isEqualTo(loanId.toString())
        assertThat(env.sourceService).isEqualTo("lending-service")
    }

    @Test
    fun `an fx conversion is an FX aggregate keyed by the conversion`() {
        val conversionId = UUID.randomUUID()
        val env = envelope(
            """
            { "conversionId": "$conversionId", "partyId": "${UUID.randomUUID()}",
              "eventType": "fx.conversion.executed", "sourceService": "fx-service" }
            """.trimIndent(),
            "openbank.fx.conversion.completed",
        )
        assertThat(env.aggregateType).isEqualTo("FX")
        assertThat(env.aggregateId).isEqualTo(conversionId.toString())
        assertThat(env.sourceService).isEqualTo("fx-service")
    }

    /**
     * The body path and the topic path must agree, or one domain gets two aggregate types depending
     * on which fields an event carries. Asserted for all four rather than spot-checked, because the
     * disagreement is invisible until a silver view groups by the pair.
     */
    @Test
    fun `body-derived and topic-derived aggregate types agree for every new domain`() {
        val cases = listOf(
            Triple("openbank.cards.events", "cardId", "CARD"),
            Triple("openbank.lending.events", "loanId", "LENDING"),
            Triple("openbank.standing-orders.order.event", "orderId", "STANDING_ORDER"),
            Triple("openbank.fx.conversion.completed", "conversionId", "FX"),
        )
        for ((topic, idField, expected) in cases) {
            val id = UUID.randomUUID()
            val fromBody = envelope("""{ "$idField": "$id" }""", topic)
            // No id field at all: only the topic can attribute, and the partition key supplies the id.
            val fromTopic = envelope("""{ "eventType": "x" }""", topic, key = id.toString())
            assertThat(fromBody.aggregateType)
                .describedAs("body-derived type for %s", topic)
                .isEqualTo(expected)
            assertThat(fromTopic.aggregateType)
                .describedAs("topic-derived type for %s", topic)
                .isEqualTo(expected)
            assertThat(fromTopic.aggregateId).isEqualTo(id.toString())
        }
    }

    /**
     * Scope from the config, never a second copy of the list: the four new topics must actually be
     * subscribed, and the Kafka ACL must grant Read on each. An ACL gap is not a red pod — the
     * consumer retries the authorization failure forever and the topic simply never arrives.
     */
    @Test
    fun `each new topic is both subscribed and granted Read`() {
        val yaml = File("src/main/resources/application.yaml").readText()
        val subscribed = Regex("^\\s*topics:\\s*(\\S+)\\s*$", RegexOption.MULTILINE)
            .find(yaml)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        val acl = File("../openbank-infra/gitops/components/analytics/kafka-analytics-sink-mtls.yaml").readText()

        for (topic in listOf(
            "openbank.cards.events",
            "openbank.lending.events",
            "openbank.standing-orders.order.event",
            "openbank.fx.conversion.completed",
        )) {
            assertThat(subscribed).describedAs("subscribed topics").contains(topic)
            assertThat(acl).describedAs("KafkaUser ACL grants Read on %s", topic).contains("name: $topic,")
        }
    }
}
