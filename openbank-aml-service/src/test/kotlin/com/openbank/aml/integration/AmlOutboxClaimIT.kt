// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.integration

import com.openbank.aml.infrastructure.persistence.repository.AmlOutboxRepositoryImpl
import com.openbank.aml.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Regression coverage for #1201: two dispatcher instances racing [AmlOutboxRepositoryImpl]'s
 * `claimProcessable` at the same instant (an Argo Rollouts canary window running old + new pod)
 * must never both claim the same row, and a claim that never reaches `markSent`/`markFailed`
 * (the claiming pod crashed or was evicted) must not strand the row forever.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlOutboxClaimIT {

    @Inject
    lateinit var repository: AmlOutboxRepositoryImpl

    @Inject
    lateinit var clock: Clock

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    // The @QuarkusTest Postgres is shared across test classes in one JVM session — without this,
    // backlog rows other IT classes left behind get scooped up by this test's claim too.
    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    private fun seedPending(count: Int): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                synthetic = true,
            )
        }
        messages.forEach { msg ->
            onEventLoop { Panache.withTransaction { repository.persistInTransaction(msg) }.awaitSuspending() }
        }
        return messages
    }

    @ParameterizedTest(name = "additive schema: {0}")
    @ValueSource(strings = ["peek", "claim"])
    fun `kernel reads preserve entity mapping after an additive schema change`(operation: String) {
        clearOutbox()
        changeTestSchema("ADD COLUMN future_attempt_at TIMESTAMPTZ, ADD COLUMN future_claim_token UUID")
        try {
            val seeded = seedPending(2)
            val before = clock.instant()
            val rows = onEventLoop {
                if (operation == "claim") repository.claimProcessable(2) else repository.listProcessable(2)
            }
            val after = clock.instant()
            assertThat(rows.map { it.eventId }).containsExactlyInAnyOrderElementsOf(seeded.map { it.eventId })
            assertThat(rows).allSatisfy { row ->
                val source = seeded.single { it.eventId == row.eventId }
                assertThat(row.aggregateId).isEqualTo(source.aggregateId)
                assertThat(row.eventType).isEqualTo(source.eventType)
                assertThat(row.payload).isEqualTo(source.payload)
                assertThat(row.synthetic).isEqualTo(source.synthetic)
                assertThat(row.createdAt).isEqualTo(source.createdAt)
                assertThat(row.attemptCount).isZero()
                assertThat(row.sentAt).isNull()
                assertThat(row.lastError).isNull()
                assertThat(row.nextAttemptAt).isNull()
                if (operation == "claim") {
                    assertThat(row.claimedAt).isBetween(before, after)
                    assertThat(row.updatedAt).isBetween(before, after)
                } else {
                    assertThat(row.claimedAt).isNull()
                    assertThat(row.updatedAt).isEqualTo(source.createdAt)
                }
                assertThat(row.status).isEqualTo(
                    if (operation == "claim") OutboxStatus.DISPATCHING else OutboxStatus.PENDING,
                )
            }
        } finally {
            try {
                clearOutbox()
            } finally {
                changeTestSchema("DROP COLUMN future_attempt_at, DROP COLUMN future_claim_token")
            }
        }
    }

    private fun changeTestSchema(clauses: String) {
        onEventLoop {
            Panache.withTransaction {
                Panache.getSession().chain { session ->
                    session.createNativeQuery<Int>("ALTER TABLE aml_outbox $clauses").executeUpdate()
                }
            }.awaitSuspending()
        }
    }

    @Test
    fun `two concurrent claims never return the same row`() {
        clearOutbox()
        val seeded = seedPending(50)
        val seededIds = seeded.map { it.eventId }.toSet()

        val (first, second) = runBlocking {
            val a = async(Dispatchers.IO) { onEventLoop { repository.claimProcessable(30) } }
            val b = async(Dispatchers.IO) { onEventLoop { repository.claimProcessable(30) } }
            awaitAll(a, b)
        }

        val firstIds = first.map { it.eventId }.toSet()
        val secondIds = second.map { it.eventId }.toSet()

        assertThat(firstIds intersect secondIds)
            .describedAs("no row may be claimed by both concurrent calls")
            .isEmpty()
        assertThat(firstIds + secondIds)
            .describedAs("every claimed row came from this test's own seed")
            .isSubsetOf(seededIds)
        assertThat((first + second)).allSatisfy { assertThat(it.status).isEqualTo(OutboxStatus.DISPATCHING) }
    }

    @Test
    fun `a stale DISPATCHING row is reclaimed instead of stranded forever`() {
        clearOutbox()
        val seeded = seedPending(1).single()

        val firstClaim = onEventLoop { repository.claimProcessable(10, Duration.ofMinutes(5)) }
        assertThat(firstClaim.map { it.eventId }).containsExactly(seeded.eventId)

        // Simulate the claiming pod crashing before markSent/markFailed: back-date claimed_at
        // far enough that any staleAfter window has already elapsed.
        onEventLoop {
            Panache.withTransaction {
                repository.update("claimedAt = ?1 where eventId = ?2", Instant.EPOCH, seeded.eventId)
            }.awaitSuspending()
        }

        val reclaim = onEventLoop { repository.claimProcessable(10, Duration.ofSeconds(1)) }
        assertThat(reclaim.map { it.eventId })
            .describedAs("stale DISPATCHING row must be reclaimable, not stranded")
            .containsExactly(seeded.eventId)
    }
}
