// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.testing.containers.PostgresTestResource
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real ledger database coverage for atomic claims, aggregate ordering and stale acknowledgements. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class LedgerOutboxClaimIT {

    @Inject
    lateinit var repository: LedgerOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    // The @QuarkusTest Postgres is shared across test classes (same convention as
    // LedgerOutboxDispatchFailureIT.clearOutbox) — without this, backlog rows other IT classes
    // left behind get scooped up by this test's claims too.
    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    private fun seedPending(count: Int, aggregateId: UUID? = null): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = aggregateId ?: Ids.newId(),
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

    @ParameterizedTest(name = "expanded schema: {0}")
    @ValueSource(booleans = [false, true])
    fun `v1 claims and acknowledges rows before and after an additive schema change`(expanded: Boolean) {
        clearOutbox()
        if (expanded) {
            changeTestSchema("ADD COLUMN next_attempt_at TIMESTAMPTZ, ADD COLUMN claim_token UUID")
        }
        try {
            val (sent, failed) = seedPending(2)
            val claims = onEventLoop { repository.claimProcessable(2) }

            assertThat(claims.map { it.eventId }).containsExactlyInAnyOrder(sent.eventId, failed.eventId)
            assertThat(claims).allSatisfy { claim ->
                val source = if (claim.eventId == sent.eventId) sent else failed
                assertThat(claim.aggregateId).isEqualTo(source.aggregateId)
                assertThat(claim.eventType).isEqualTo(source.eventType)
                assertThat(claim.payload).isEqualTo(source.payload)
                assertThat(claim.status).isEqualTo(OutboxStatus.DISPATCHING)
            }
            onEventLoop { repository.markSent(sent.eventId) }
            assertThat(onEventLoop { repository.markFailed(failed.eventId, "synthetic failure") })
                .isEqualTo(OutboxStatus.FAILED)

            val sentRow = requireNotNull(
                onEventLoop {
                    Panache.withSession { repository.find("eventId", sent.eventId).firstResult() }.awaitSuspending()
                },
            )
            assertThat(sentRow.status).isEqualTo(OutboxStatus.SENT.name)
            assertThat(sentRow.attemptCount).isOne()
            assertThat(sentRow.sentAt).isNotNull
            val retry = onEventLoop { repository.claimProcessable(2) }.single()
            assertThat(retry.eventId).isEqualTo(failed.eventId)
            assertThat(retry.attemptCount).isOne()
            assertThat(retry.lastError).isEqualTo("synthetic failure")
        } finally {
            if (expanded) {
                changeTestSchema("DROP COLUMN next_attempt_at, DROP COLUMN claim_token")
            }
        }
    }

    private fun changeTestSchema(clauses: String) {
        onEventLoop {
            Panache.withTransaction {
                Panache.getSession().chain { session ->
                    session.createNativeQuery<Int>("ALTER TABLE ledger_outbox $clauses").executeUpdate()
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
            val a = async(Dispatchers.IO) { onEventLoop { repository.claimWithTokens(30) } }
            val b = async(Dispatchers.IO) { onEventLoop { repository.claimWithTokens(30) } }
            awaitAll(a, b)
        }

        val firstIds = first.map { it.entry.eventId }.toSet()
        val secondIds = second.map { it.entry.eventId }.toSet()

        assertThat(firstIds intersect secondIds)
            .describedAs("no row may be claimed by both concurrent calls")
            .isEmpty()
        assertThat(firstIds + secondIds)
            .describedAs("every claimed row came from this test's own seed")
            .isSubsetOf(seededIds)
        assertThat((first + second)).allSatisfy { assertThat(it.entry.status).isEqualTo(OutboxStatus.DISPATCHING) }
    }

    @Test
    fun `a stale DISPATCHING row is reclaimed instead of stranded forever`() {
        clearOutbox()
        val seeded = seedPending(1).single()

        val first = onEventLoop { repository.claimWithTokens(10, Duration.ofMinutes(5)) }.single()
        assertThat(first.entry.eventId).isEqualTo(seeded.eventId)

        // Simulate the claiming pod crashing before acknowledgement: back-date claimed_at
        // far enough that any staleAfter window has already elapsed.
        onEventLoop {
            Panache.withTransaction {
                repository.update("claimedAt = ?1 where eventId = ?2", Instant.EPOCH, seeded.eventId)
            }.awaitSuspending()
        }

        val replacement = onEventLoop { repository.claimWithTokens(10, Duration.ofSeconds(1)) }.single()
        assertThat(replacement.entry.eventId).isEqualTo(seeded.eventId)
        assertThat(replacement.token).isNotEqualTo(first.token)
        assertThat(onEventLoop { repository.markClaimsSent(listOf(first)) }).isEmpty()
        assertThat(onEventLoop { repository.markClaimFailed(first, "late failure") }).isNull()
        assertThat(onEventLoop { repository.markClaimsSent(listOf(replacement)) }).containsExactly(seeded.eventId)
        assertThat(onEventLoop { repository.markClaimFailed(first, "late failure after send") }).isNull()
    }

    @Test
    fun `an aggregate tail is claimable only after its head is acknowledged`() {
        clearOutbox()
        val (head, tail) = seedPending(2, Ids.newId())
        val claim = onEventLoop { repository.claimWithTokens(2) }.single()
        assertThat(claim.entry.eventId).isEqualTo(head.eventId)
        assertThat(onEventLoop { repository.claimWithTokens(2) }).isEmpty()

        assertThat(onEventLoop { repository.markClaimsSent(listOf(claim)) }).containsExactly(head.eventId)
        assertThat(onEventLoop { repository.claimWithTokens(2) }.map { it.entry.eventId }).containsExactly(tail.eventId)
    }
}
