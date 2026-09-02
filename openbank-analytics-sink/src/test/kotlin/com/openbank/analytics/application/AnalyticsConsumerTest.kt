// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.PiiMask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Plain-JUnit tests for the event → [com.openbank.libs.analytics.AnalyticsEnvelope] projection and
 * the at-the-sink PII masking. No @QuarkusTest boot — the mapping is pure, so it stays fast and
 * offline-runnable (this service has no IT scaffold yet).
 */
class AnalyticsConsumerTest {

    private val mapper = ObjectMapper()
    private val consumer = AnalyticsConsumer().apply {
        objectMapper = mapper
        clock = Clock.systemUTC()
    }

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
            """.trimIndent(),
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
    fun `persists synthetic provenance from broker metadata`() {
        val node = mapper.readTree("""{ "aggregateId": "canary-1", "eventType": "payment.created" }""")

        val env = consumer.toEnvelope(node, EventAddress(synthetic = true))

        assertThat(env.synthetic).isTrue()
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
    fun `server attributed engagement stays a distinct analytics fact`() {
        val eventId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val node = mapper.readTree(
            """
            {
              "eventId": "$eventId",
              "aggregateType": "ENGAGEMENT",
              "aggregateId": "$eventId",
              "partyId": "${UUID.randomUUID()}",
              "campaignId": "$campaignId",
              "stepOrder": 2,
              "channel": "PUSH",
              "type": "CLICK",
              "occurredAt": "2026-08-13T10:00:00Z"
            }
            """.trimIndent(),
        )

        val env = consumer.toEnvelope(
            node,
            EventAddress(
                topic = "openbank.engagement.events",
                key = UUID.randomUUID().toString(),
                ceType = "EngagementEvent.CLICK",
            ),
        )

        assertThat(env.aggregateType).isEqualTo("ENGAGEMENT")
        assertThat(env.aggregateId).isEqualTo(eventId.toString())
        assertThat(env.eventType).isEqualTo("EngagementEvent.CLICK")
        assertThat(env.sourceService).isEqualTo("openbank-engagement-service")
        assertThat(env.payload).containsEntry("campaignId", campaignId.toString())
        assertThat(env.payload).containsEntry("stepOrder", 2L)
        assertThat(env.payload).containsEntry("channel", "PUSH")
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
            """.trimIndent(),
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
            """.trimIndent(),
        )

        @Suppress("UNCHECKED_CAST")
        val contact = consumer.toEnvelope(node).payload["contact"] as Map<String, Any?>
        assertThat(contact["phone"]).isEqualTo(PiiMask.phone("+420123456789"))
    }

    // ── Domain inference (#2598) ───────────────────────────────────────────────────────────────
    //
    // The sink was subscribed to `openbank.account.events` and `openbank.transaction.events`, and
    // NEITHER topic has ever existed (the real ones are `openbank.accounts.account.created` and
    // `openbank.transactions.transaction.initiated`). A consumer subscribed to a nonexistent topic
    // just receives nothing, so nothing went red — for as long as the sink ran, no ACCOUNT_OPENED and
    // no TRANSACTION row ever reached bronze, and ADR-0210 D2's account->party resolution was dead
    // code in practice.
    //
    // These tests cover what correcting the topic names EXPOSES, which is the reason both changes
    // ship together.

    @Test
    fun `a transaction event is a TRANSACTION even though it also carries an accountId`() {
        // The latent bug the topic fix would have made live: `accountId` was tested first, so every
        // transaction would have landed in bronze as ACCOUNT the moment the real topic was consumed.
        val node = mapper.readTree(
            """
            {"transactionId":"txn-1","accountId":"acc-9","amount":100,"eventType":"TRANSACTION_POSTED"}
            """.trimIndent(),
        )
        val env = consumer.toEnvelope(node)
        assertThat(env.aggregateType).isEqualTo("TRANSACTION")
        // And the id must AGREE with the type: bronze_events is keyed (aggregate_type, aggregate_id)
        // and silver reduces per that key, so pairing TRANSACTION with an account id would collapse
        // every transaction on one account into a single aggregate.
        assertThat(env.aggregateId).isEqualTo("txn-1")
    }

    @Test
    fun `an account event is still an ACCOUNT`() {
        val node = mapper.readTree("""{"accountId":"acc-9","eventType":"ACCOUNT_OPENED"}""")
        val env = consumer.toEnvelope(node)
        assertThat(env.aggregateType).isEqualTo("ACCOUNT")
        assertThat(env.aggregateId).isEqualTo("acc-9")
    }

    @Test
    fun `a generated-document event is a DOCUMENT, not UNKNOWN`() {
        // Measured in the sandbox: this exact shape landed as UNKNOWN/UNKNOWN/unknown — three columns
        // of attribution blank on an event whose payload names the template it produced.
        val node = mapper.readTree(
            """
            {"documentId":"doc-1","templateCode":"RAMCOVA_SMLOUVA_CS","templateVersion":"1.1.0"}
            """.trimIndent(),
        )
        val env = consumer.toEnvelope(node)
        assertThat(env.aggregateType).isEqualTo("DOCUMENT")
        assertThat(env.aggregateId).isEqualTo("doc-1")
    }

    @Test
    fun `a signing-ceremony event resolves to the document it is about`() {
        val node = mapper.readTree("""{"ceremonyId":"cer-1","documentId":"doc-1"}""")
        assertThat(consumer.toEnvelope(node).aggregateType).isEqualTo("DOCUMENT")
    }

    @Test
    fun `a passkey registration is a PASSKEY, not a bare PARTY`() {
        // Also seen in the sandbox as PARTY/UNKNOWN: correct domain, no event type, and the
        // credential it registered indistinguishable from any other party change.
        val node = mapper.readTree(
            """
            {"credentialId":"cred-1","deviceId":"dev-1","partyId":"pty-1"}
            """.trimIndent(),
        )
        val env = consumer.toEnvelope(node)
        assertThat(env.aggregateType).isEqualTo("PASSKEY")
        assertThat(env.aggregateId).isEqualTo("cred-1")
    }

    @Test
    fun `an explicit aggregateType always wins over inference`() {
        // Inference is the fallback, never an override — an envelope that states its type must be
        // taken at its word even when the payload keys suggest otherwise.
        val node = mapper.readTree(
            """
            {"aggregateType":"LEDGER","aggregateId":"led-1","transactionId":"txn-1"}
            """.trimIndent(),
        )
        val env = consumer.toEnvelope(node)
        assertThat(env.aggregateType).isEqualTo("LEDGER")
        assertThat(env.aggregateId).isEqualTo("led-1")
    }
}
