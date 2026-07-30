// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuditConsumerTest {

    private val repo = mockk<AuditRepository>()

    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
            // ADR-0100: recordedAt is stamped via Instant.now(clock); fix it for determinism.
            it.clock = Clock.fixed(Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC)
        }
    }

    @Test
    fun `consume records audit entry with inferred aggregate type and fallback actor fields`(): Unit = runBlocking {
        val transactionId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-05-27T12:00:00Z")
        val payload = """
            {
              "eventType": "TRANSACTION_FAILED",
              "transactionId": "$transactionId",
              "requestedBy": "operator-7",
              "sourceService": "transaction-service",
              "correlationId": "corr-123",
              "occurredAt": "$occurredAt"
            }
        """.trimIndent()

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.eventType == "TRANSACTION_FAILED" &&
                        it.aggregateType == "TRANSACTION" &&
                        it.aggregateId == transactionId.toString() &&
                        it.actorId == "operator-7" &&
                        it.sourceService == "transaction-service" &&
                        it.correlationId == "corr-123" &&
                        it.occurredAt == occurredAt &&
                        it.payload == payload
                },
            )
        }
    }

    @Test
    fun `consume records the cross-channel dimensions when the producer sends them (ADR-0226)`(): Unit = runBlocking {
        val payload = """
            {
              "eventType": "mcp.tool.call",
              "actorId": "agent:test-agent",
              "actorType": "AI_AGENT",
              "accountId": "${UUID.randomUUID()}",
              "sourceService": "mcp-service",
              "channel": "mcp",
              "actChain": ["agent-session:7f3a", "mcp-cli"],
              "sessionId": "sess-123"
            }
        """.trimIndent()

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.channel == "mcp" &&
                        it.actChain == listOf("agent-session:7f3a", "mcp-cli") &&
                        it.sessionId == "sess-123"
                },
            )
        }
    }

    @Test
    fun `consume leaves the cross-channel dimensions unknown when the producer omits them`(): Unit = runBlocking {
        val payload = """
            {"eventType":"TRANSACTION_FAILED","transactionId":"${UUID.randomUUID()}","requestedBy":"operator-7"}
        """.trimIndent()

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match { it.channel == null && it.actChain.isEmpty() && it.sessionId == null },
            )
        }
    }

    @Test
    fun `consume extracts batchId as aggregateId for a clearing batch-settled event`(): Unit = runBlocking {
        val batchId = UUID.randomUUID()
        val payload = """
            {"eventType":"openbank.clearing.batch.settled","batchId":"$batchId","rail":"SEPA"}
        """.trimIndent()

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.eventType == "openbank.clearing.batch.settled" &&
                        it.aggregateType == "CLEARING_BATCH" &&
                        it.aggregateId == batchId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts itemId as aggregateId for a clearing item-cleared event`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val payload = """{"eventType":"openbank.clearing.item.cleared","itemId":"$itemId"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "CLEARING_ITEM" && it.aggregateId == itemId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts the nested incident id for an ICT incident event`(): Unit = runBlocking {
        val incidentId = UUID.randomUUID()
        val payload = """
            {"eventType":"ICT_INCIDENT_REPORTED","incident":{"id":"$incidentId","severity":"P1_CRITICAL"}}
        """.trimIndent()

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.eventType == "ICT_INCIDENT_REPORTED" &&
                        it.aggregateType == "ICT_INCIDENT" &&
                        it.aggregateId == incidentId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts cardId as aggregateId for a card status-changed event`(): Unit = runBlocking {
        val cardId = UUID.randomUUID()
        val payload = """{"eventType":"CARD_STATUS_CHANGED","cardId":"$cardId","newStatus":"BLOCKED"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "CARD" && it.aggregateId == cardId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts disputeId as aggregateId for a dispute-resolved event`(): Unit = runBlocking {
        val disputeId = UUID.randomUUID()
        val payload = """{"eventType":"dispute.resolved","disputeId":"$disputeId","outcome":"UPHELD"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "DISPUTE" && it.aggregateId == disputeId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts paymentId as aggregateId for a payment status-changed event`(): Unit = runBlocking {
        val paymentId = UUID.randomUUID()
        val payload = """{"eventType":"PAYMENT_STATUS_CHANGED","paymentId":"$paymentId","status":"VALIDATED"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "PAYMENT" && it.aggregateId == paymentId.toString()
                },
            )
        }
    }

    @Test
    fun `consume falls back to the type field for eventType on a SEPA Instant event`(): Unit = runBlocking {
        val paymentId = UUID.randomUUID()
        // KafkaSctInstEventPublisher's wire shape: {"type": <event class name>, "paymentId",
        // "occurredAt"} — no "eventType" key.
        val payload =
            """{"type":"SctInstPaymentSettled","paymentId":"$paymentId","occurredAt":"2026-07-14T00:00:00Z"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.eventType == "SctInstPaymentSettled" &&
                        it.aggregateType == "PAYMENT" &&
                        it.aggregateId == paymentId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts documentId as aggregateId for a document-generated event`(): Unit = runBlocking {
        val documentId = UUID.randomUUID()
        val payload = """{"eventType":"DOCUMENT_GENERATED","documentId":"$documentId","templateCode":"x"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "DOCUMENT" && it.aggregateId == documentId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts ceremonyId as aggregateId for a signature-ceremony-completed event`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        val payload = """{"eventType":"SIGNATURE_CEREMONY_COMPLETED","ceremonyId":"$ceremonyId"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "SIGNATURE_CEREMONY" && it.aggregateId == ceremonyId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts conversionId as aggregateId for an fx-conversion-executed event`(): Unit = runBlocking {
        val conversionId = UUID.randomUUID()
        val payload = """{"eventType":"fx.conversion.executed.v1","conversionId":"$conversionId","toAmount":100}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "FX_CONVERSION" && it.aggregateId == conversionId.toString()
                },
            )
        }
    }

    @Test
    fun `consume extracts swiftMessageId as aggregateId for a swift-message-status-changed event`(): Unit =
        runBlocking {
            val swiftMessageId = UUID.randomUUID()
            val payload =
                """{"eventType":"SWIFT_MESSAGE_STATUS_CHANGED","swiftMessageId":"$swiftMessageId","status":"SENT"}"""

            coEvery { repo.save(any()) } returns Unit

            consumer.consume(payload)

            coVerify {
                repo.save(
                    match {
                        it.aggregateType == "SWIFT_MESSAGE" && it.aggregateId == swiftMessageId.toString()
                    },
                )
            }
        }

    @Test
    fun `consume extracts the bare id as aggregateId for a sanctions screening event`(): Unit = runBlocking {
        val checkId = UUID.randomUUID()
        val payload = """{"eventType":"sanctions.check.completed.v1","id":"$checkId","status":"CLEAR"}"""

        coEvery { repo.save(any()) } returns Unit

        consumer.consume(payload)

        coVerify {
            repo.save(
                match {
                    it.aggregateType == "SANCTIONS_CHECK" && it.aggregateId == checkId.toString()
                },
            )
        }
    }

    @Test
    fun `consume swallows malformed payloads without persisting`() {
        assertThatCode {
            runBlocking { consumer.consume("{bad-json") }
        }.doesNotThrowAnyException()

        coVerify(exactly = 0) { repo.save(any()) }
    }
}
