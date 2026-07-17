// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.transaction.infrastructure.persistence.repository.TransactionOutboxRepositoryImpl
import com.openbank.transaction.it.PostgresRedpandaTestResource
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
 * Regression coverage for #1201: two dispatcher instances racing
 * [TransactionOutboxRepositoryImpl]'s `claimProcessable` at the same instant (an Argo Rollouts
 * canary window running old + new pod) must never both claim the same row, and a claim that
 * never reaches `markSent`/`markFailed` (the claiming pod crashed or was evicted) must not
 * strand the row forever.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionOutboxClaimIT {

    @Inject
    lateinit var repository: TransactionOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    private fun seedPending(count: Int): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now(),
            )
        }
        // TransactionOutboxRepositoryImpl.persistInTransaction is `override suspend fun
        // persistInTransaction(message: OutboxMessage) { persist(message.toEntity()).awaitSuspending() }`
        // -- it does NOT open its own session/transaction; it relies on the *caller* already
        // having one open (by design: it exists so a business method can persist the outbox row
        // inside the same transaction as the aggregate write it's paired with). Calling it bare
        // from a fresh Vert.x context throws "No current Mutiny.Session found" (verified). The
        // repository's own `persistMessageUni` helper -- the same one production code
        // (PanacheTransactionRepository) chains under its own Panache.withTransaction -- returns
        // a plain Uni, so it composes directly as the Panache.withTransaction supplier here.
        messages.forEach { msg ->
            onEventLoop { Panache.withTransaction { repository.persistMessageUni(msg) }.awaitSuspending() }
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
