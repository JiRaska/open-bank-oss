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
import java.time.Instant
import java.util.UUID

class AuditConsumerTest {

    private val repo = mockk<AuditRepository>()

    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
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
            repo.save(match {
                it.eventType == "TRANSACTION_FAILED" &&
                    it.aggregateType == "TRANSACTION" &&
                    it.aggregateId == transactionId.toString() &&
                    it.actorId == "operator-7" &&
                    it.sourceService == "transaction-service" &&
                    it.correlationId == "corr-123" &&
                    it.occurredAt == occurredAt &&
                    it.payload == payload
            })
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