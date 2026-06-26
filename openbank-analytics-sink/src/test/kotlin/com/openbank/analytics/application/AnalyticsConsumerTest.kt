// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.PiiMask
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-JUnit tests for the event → [com.openbank.libs.analytics.AnalyticsEnvelope] projection and
 * the at-the-sink PII masking. No @QuarkusTest boot — the mapping is pure, so it stays fast and
 * offline-runnable (this service has no IT scaffold yet).
 */
class AnalyticsConsumerTest {

    private val mapper = ObjectMapper()
    private val consumer = AnalyticsConsumer().apply { objectMapper = mapper }

    @Test
    fun `maps canonical fields from an outbox event`() {
        val eventId = UUID.randomUUID()
        val node = mapper.readTree(
            """
            {
              "eventId": "$eventId",
              "aggregateType": "ACCOUNT",
              "aggregateId": "acc-42",
              "aggregateVersion": 7,
              "eventType": "account.changed",
              "occurredAt": "2026-01-01T00:00:00Z",
              "sourceService": "openbank-account-service",
              "schemaVersion": 3,
              "actorId": "user-1",
              "actorType": "ROLE_OPERATOR",
              "traceId": "trace-9"
            }
            """.trimIndent()
        )

        val env = consumer.toEnvelope(node)

        assertThat(env.eventId).isEqualTo(eventId)
        assertThat(env.aggregateType).isEqualTo("ACCOUNT")
        assertThat(env.aggregateId).isEqualTo("acc-42")
        assertThat(env.aggregateVersion).isEqualTo(7)
        assertThat(env.eventType).isEqualTo("account.changed")
        assertThat(env.sourceService).isEqualTo("openbank-account-service")
        assertThat(env.schemaVersion).isEqualTo(3)
        assertThat(env.actorId).isEqualTo("user-1")
        assertThat(env.traceId).isEqualTo("trace-9")
    }

    @Test
    fun `infers aggregate type and id when not explicit`() {
        val node = mapper.readTree("""{ "partyId": "p-1", "eventType": "party.created" }""")

        val env = consumer.toEnvelope(node)

        assertThat(env.aggregateType).isEqualTo("PARTY")
        assertThat(env.aggregateId).isEqualTo("p-1")
        assertThat(env.aggregateVersion).isEqualTo(0)
    }

    @Test
    fun `generates a random eventId when source omits it so dedupe never NPEs`() {
        val node = mapper.readTree("""{ "aggregateId": "x", "eventType": "e" }""")

        assertThat(consumer.toEnvelope(node).eventId).isNotNull()
    }

    @Test
    fun `masks PII in the payload before it reaches the sink`() {
        val node = mapper.readTree(
            """
            {
              "aggregateType": "PARTY",
              "aggregateId": "p-1",
              "eventType": "party.created",
              "payload": {
                "email": "john.doe@example.com",
                "iban": "CZ6508000000192000145399",
                "fullName": "Jiri Raska",
                "balance": 1234,
                "active": true
              }
            }
            """.trimIndent()
        )

        val payload = consumer.toEnvelope(node).payload

        assertThat(payload["email"]).isEqualTo(PiiMask.email("john.doe@example.com"))
        assertThat(payload["iban"]).isEqualTo(PiiMask.iban("CZ6508000000192000145399"))
        assertThat(payload["fullName"]).isEqualTo(PiiMask.name("Jiri Raska"))
        // Non-PII structural fields pass through intact (they carry the analytic value).
        assertThat(payload["balance"]).isEqualTo(1234L)
        assertThat(payload["active"]).isEqualTo(true)
    }

    @Test
    fun `masks nested PII recursively`() {
        val node = mapper.readTree(
            """
            {
              "aggregateId": "p-1",
              "eventType": "party.updated",
              "payload": { "contact": { "phone": "+420123456789" }, "tags": ["vip", "new"] }
            }
            """.trimIndent()
        )

        @Suppress("UNCHECKED_CAST")
        val contact = consumer.toEnvelope(node).payload["contact"] as Map<String, Any?>
        assertThat(contact["phone"]).isEqualTo(PiiMask.phone("+420123456789"))
    }
}
