// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.integration

import com.openbank.domestic.infrastructure.persistence.repository.DomesticPaymentOutboxRepositoryImpl
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
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

/**
 * Regression coverage for #1201: two dispatcher instances racing
 * [DomesticPaymentOutboxRepositoryImpl]'s `claimProcessable` at the same instant (an Argo
 * Rollouts canary window running old + new pod) must never both claim the same row, and a claim
 * that never reaches `markSent`/`markFailed` (the claiming pod crashed or was evicted) must not
 * strand the row forever.
 */
@QuarkusTest
@TestProfile(DomesticPaymentOutboxClaimIT.NoDispatchProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DomesticPaymentOutboxClaimIT {

    // This test owns the first claim. The production dispatcher runs on its own schedule and
    // would otherwise race it for the seeded row, making the first-claim assertion flaky.
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: DomesticPaymentOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    // DomesticPaymentOutboxRepositoryImpl has no generic persistInTransaction override -- its
    // seed hook is the bespoke, non-interface persistWithinCurrentTransaction(OutboxMessage),
    // which returns the persisted entity (Uni<DomesticPaymentOutboxEntity>), not Uni<Void>.
    private fun seedPending(count: Int, synthetic: Boolean = false): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now(),
                synthetic = synthetic,
            )
        }
        messages.forEach { msg ->
            onEventLoop {
                Panache.withTransaction { repository.persistWithinCurrentTransaction(msg) }.awaitSuspending()
            }
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
            val (sent, failed) = seedPending(2, synthetic = true)
            val claims = onEventLoop { repository.claimProcessable(2) }

            assertThat(claims.map { it.eventId }).containsExactlyInAnyOrder(sent.eventId, failed.eventId)
            assertThat(claims).allSatisfy { claim ->
                val source = if (claim.eventId == sent.eventId) sent else failed
                assertThat(claim.aggregateId).isEqualTo(source.aggregateId)
                assertThat(claim.eventType).isEqualTo(source.eventType)
                assertThat(claim.payload).isEqualTo(source.payload)
                assertThat(claim.status).isEqualTo(OutboxStatus.DISPATCHING)
                assertThat(claim.synthetic).isTrue()
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
                    session.createNativeQuery<Int>("ALTER TABLE domestic_payment_outbox $clauses").executeUpdate()
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
