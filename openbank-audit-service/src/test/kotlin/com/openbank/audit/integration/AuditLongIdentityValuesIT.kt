// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.application.AuditConsumer
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A producer-supplied identity value longer than 100 characters is recorded, verbatim and under
 * the chain. It runs against the real schema because the defect lives in the DDL: a mocked
 * repository accepts any length. All six columns carry a long value in one event, so narrowing any
 * single one of them fails the INSERT with SQLState 22001 and turns this test red.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AuditLongIdentityValuesIT {

    @Inject
    lateinit var repository: AuditRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun consumer() = AuditConsumer().also {
        it.repo = repository
        it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
        it.clock = java.time.Clock.systemUTC()
    }

    private fun longValue(field: String, unique: UUID) = "$field-$unique-".padEnd(COLUMN_LIMIT, 'x')

    @Test
    fun `values at the column limit are stored verbatim and the chain verifies`() {
        val unique = UUID.randomUUID()
        val eventType = longValue("eventType", unique)
        val aggregateId = longValue("aggregateId", unique)
        val actorId = longValue("actorId", unique)
        val sourceService = longValue("sourceService", unique)
        val correlationId = longValue("correlationId", unique)
        val sessionId = longValue("sessionId", unique)

        onEventLoop {
            consumer().consume(
                """
                {
                  "eventId": "$unique",
                  "eventType": "$eventType",
                  "aggregateType": "CUSTOMER_ACTION",
                  "aggregateId": "$aggregateId",
                  "actorId": "$actorId",
                  "actorType": "CUSTOMER",
                  "sourceService": "$sourceService",
                  "correlationId": "$correlationId",
                  "sessionId": "$sessionId",
                  "occurredAt": "2026-08-02T09:15:00.123456Z"
                }
                """.trimIndent(),
            )
        }

        val entry = onEventLoop { repository.findByActorId(actorId) }.single()
        assertThat(entry.eventType).isEqualTo(eventType)
        assertThat(entry.aggregateId).isEqualTo(aggregateId)
        assertThat(entry.actorId).isEqualTo(actorId)
        assertThat(entry.sourceService).isEqualTo(sourceService)
        assertThat(entry.correlationId).isEqualTo(correlationId)
        assertThat(entry.sessionId).isEqualTo(sessionId)

        val verification = onEventLoop { repository.verifyChain(fromEntryId = entry.id) }
        assertThat(verification.intact)
            .describedAs("chain must verify from the long entry (broken at: %s)", verification.firstBrokenEntryId)
            .isTrue()
    }

    private companion object {
        const val COLUMN_LIMIT = 512
    }
}
