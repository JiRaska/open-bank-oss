// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.application.ContextAuditCommitmentConsumer
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Real database proof of strict ingest, deduplication and hash-chain inclusion. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ContextAuditCommitmentIT {
    @Inject lateinit var consumer: ContextAuditCommitmentConsumer

    @Inject lateinit var repository: AuditRepository

    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `same commitment is stored once and conflicting redelivery fails`() {
        val id = UUID.randomUUID()
        val payload = commitment(id, "a".repeat(64))

        onEventLoop {
            consumer.persist(payload)
            consumer.persist(payload)
        }

        assertThat(onEventLoop { repository.findByAggregateId(id.toString()) }.map { it.id })
            .containsExactly(id)
        val verification = onEventLoop { repository.verifyChain(fromEntryId = id) }
        assertThat(verification.intact)
            .describedAs("commitment chain link must verify (broken: %s)", verification.firstBrokenEntryId)
            .isTrue()
        assertThat(verification.checked).isGreaterThanOrEqualTo(1)
        assertThatThrownBy {
            onEventLoop { consumer.persist(commitment(id, "b".repeat(64))) }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `disclosure commitment is chained once and conflicting replay is rejected`() {
        val id = UUID.randomUUID()
        val payload = commitment(id, "c".repeat(64), "CONTEXT_DISCLOSURE_COMMITTED", "CONTEXT_DISCLOSURE")

        onEventLoop {
            consumer.persist(payload)
            consumer.persist(payload)
        }

        assertThat(onEventLoop { repository.findByAggregateId(id.toString()) }.map { it.id })
            .containsExactly(id)
        assertThat(onEventLoop { repository.verifyChain(fromEntryId = id) }.intact).isTrue()
        assertThatThrownBy {
            onEventLoop {
                consumer.persist(commitment(id, "d".repeat(64), "CONTEXT_DISCLOSURE_COMMITTED", "CONTEXT_DISCLOSURE"))
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun commitment(
        id: UUID,
        digest: String,
        eventType: String = "CONTEXT_READ_AUDIT_COMMITTED",
        aggregateType: String = "CONTEXT_READ_AUDIT",
    ): String = mapper.writeValueAsString(
        mapOf(
            "schemaVersion" to 1,
            "eventId" to id.toString(),
            "eventType" to eventType,
            "aggregateType" to aggregateType,
            "aggregateId" to id.toString(),
            "sourceService" to "context-service",
            "occurredAt" to Instant.parse("2026-09-18T12:00:00Z").toString(),
            "commitment" to digest,
        ),
    )

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
