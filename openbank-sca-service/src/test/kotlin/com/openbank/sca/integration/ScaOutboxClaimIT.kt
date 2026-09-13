// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sca.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sca.infrastructure.persistence.repository.ScaOutboxRepositoryImpl
import com.openbank.sca.it.PostgresRedisTestResource
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

/**
 * Regression coverage for #1201: two dispatcher instances racing [ScaOutboxRepositoryImpl]'s
 * `claimProcessable` at the same instant (an Argo Rollouts canary window running old + new pod)
 * must never both claim the same row, and a claim that never reaches `markSent`/`markFailed`
 * (the claiming pod crashed or was evicted) must not strand the row forever.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaOutboxClaimIT {

    @Inject
    lateinit var repository: ScaOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    // persistInTransaction opens NO transaction of its own (#8679) -- that is what lets the
    // enrolled device and its event commit together -- so the seed supplies one here, exactly as
    // document-service's and fx-service's claim ITs do.
    private fun seedPending(count: Int): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now(),
            )
        }
        messages.forEach { msg ->
            onEventLoop { Panache.withTransaction { repository.persistInTransaction(msg) }.awaitSuspending() }
        }
        return messages
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
