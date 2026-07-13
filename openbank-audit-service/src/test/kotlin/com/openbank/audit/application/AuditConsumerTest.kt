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
    fun `consume swallows malformed payloads without persisting`() {
        assertThatCode {
            runBlocking { consumer.consume("{bad-json") }
        }.doesNotThrowAnyException()

        coVerify(exactly = 0) { repo.save(any()) }
    }
}
