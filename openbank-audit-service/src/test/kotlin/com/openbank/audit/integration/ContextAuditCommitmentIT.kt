// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.application.ContextAuditCommitmentConsumer
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ContextCommitmentReceiptEntity
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
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
        val receipt = onEventLoop {
            Panache.withSession {
                Panache.getSession().flatMap { session -> session.find(ContextCommitmentReceiptEntity::class.java, id) }
            }
                .awaitSuspending()
        }
        requireNotNull(receipt)
        assertThat(receipt.commitment).isEqualTo("a".repeat(64))
        assertThat(receipt.recordHash).matches("[0-9a-f]{64}")
        assertThat(receipt.status).isEqualTo("PENDING")
        val verification = onEventLoop { repository.verifyChain(fromEntryId = id) }
        assertThat(verification.intact)
            .describedAs("commitment chain link must verify (broken: %s)", verification.firstBrokenEntryId)
            .isTrue()
        assertThat(verification.checked).isGreaterThanOrEqualTo(1)
        assertThatThrownBy {
            onEventLoop { consumer.persist(commitment(id, "b".repeat(64))) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        val differentId = UUID.randomUUID()
        val sourceEntry = onEventLoop { repository.findByAggregateId(id.toString()).single() }
        val forged = sourceEntry.copy(
            id = differentId,
            aggregateId = differentId.toString(),
            payload = commitment(differentId, "a".repeat(64)),
        )
        assertThatThrownBy {
            onEventLoop { repository.save(forged, "b".repeat(64)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(onEventLoop { repository.findByAggregateId(differentId.toString()) }).isEmpty()
    }

    private fun commitment(id: UUID, digest: String): String = mapper.writeValueAsString(
        mapOf(
            "schemaVersion" to 1,
            "eventId" to id.toString(),
            "eventType" to "CONTEXT_READ_AUDIT_COMMITTED",
            "aggregateType" to "CONTEXT_READ_AUDIT",
            "aggregateId" to id.toString(),
            "sourceService" to "context-service",
            "occurredAt" to Instant.parse("2026-09-18T12:00:00Z").toString(),
            "commitment" to digest,
        ),
    )

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
