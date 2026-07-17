// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.billing.integration

import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
import com.openbank.billing.infrastructure.persistence.entity.BillingOutboxEntity
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Regression coverage for #1201: two dispatcher instances racing [BillingOutboxRepositoryImpl]'s
 * `claimProcessable` at the same instant (an Argo Rollouts canary window running old + new pod)
 * must never both claim the same row, and a claim that never reaches `markSent`/`markFailed`
 * (the claiming pod crashed or was evicted) must not strand the row forever.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingOutboxClaimIT {

    @Inject
    lateinit var repository: BillingOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    // The @QuarkusTest Postgres is shared across test classes in one JVM session — without this,
    // backlog rows other IT classes left behind get scooped up by this test's claim too.
    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    private fun seedEntity(seq: Int): UUID {
        val eventId = Ids.newId()
        onEventLoop {
            Panache.withTransaction {
                val e = BillingOutboxEntity()
                e.eventId = eventId
                e.aggregateId = Ids.newId()
                e.eventType = "test.event.claim"
                e.payload = """{"seq":$seq}"""
                e.status = OutboxStatus.PENDING.name
                e.attemptCount = 0
                e.createdAt = Instant.now()
                e.updatedAt = Instant.now()
                repository.persist(e)
            }.awaitSuspending()
        }
        return eventId
    }

    private fun seedPending(count: Int): List<UUID> = (1..count).map { seedEntity(it) }

    @Test
    fun `two concurrent claims never return the same row`() {
        clearOutbox()
        val seededIds = seedPending(50).toSet()

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
        val seededId = seedPending(1).single()

        val firstClaim = onEventLoop { repository.claimProcessable(10, Duration.ofMinutes(5)) }
        assertThat(firstClaim.map { it.eventId }).containsExactly(seededId)

        // Simulate the claiming pod crashing before markSent/markFailed: back-date claimed_at
        // far enough that any staleAfter window has already elapsed.
        onEventLoop {
            Panache.withTransaction {
                repository.update("claimedAt = ?1 where eventId = ?2", Instant.EPOCH, seededId)
            }.awaitSuspending()
        }

        val reclaim = onEventLoop { repository.claimProcessable(10, Duration.ofSeconds(1)) }
        assertThat(reclaim.map { it.eventId })
            .describedAs("stale DISPATCHING row must be reclaimable, not stranded")
            .containsExactly(seededId)
    }
}
