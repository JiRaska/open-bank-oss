// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.integration

import com.openbank.cardissuance.infrastructure.persistence.entity.CardOutboxEntity
import com.openbank.cardissuance.infrastructure.persistence.repository.CardOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #4005 — **an open circuit breaker must not drive outbox rows to terminal `DEAD`.**
 *
 * What the live table looked like: all 24 `card_outbox` rows `DEAD`, every one at
 * `attempt_count = 10` (`OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS`), every one carrying the *same*
 * `last_error` — `CardOutboxDispatcher#publishWithResilience circuit breaker is open`. So none of
 * those 240 recorded "attempts" was a delivery attempt: the breaker short-circuited before the
 * publisher was ever called, each 5 s tick re-claimed the whole batch, and ~50 s of breaker-open was
 * enough to make every row terminal. `DEAD` is excluded from `listProcessable`, so nothing retried
 * them again and the breaker's own half-open probe had no work left to probe with.
 *
 * ### Why this is an IT and not a unit test
 * The bookkeeping under test is `markFailed`'s attempt increment plus [OutboxFailurePolicy], which
 * lives in the repository and commits to Postgres. A mocked repository would assert only that a
 * method was (not) called — it cannot show the row's committed status or `attempt_count`, which is
 * the entire claim. So the repository here is the **real** [CardOutboxRepositoryImpl] against the
 * Testcontainers Postgres, and the assertions are a plain JDBC read on a separate connection —
 * outside the reactive session, so nothing can be answered out of a first-level cache.
 *
 * The publisher is the only stubbed part, because [CircuitBreakerOpenException] is precisely what a
 * real `@CircuitBreaker` interceptor throws *instead of* invoking the publish. Driving it through
 * the annotated `CardOutboxDispatcher.publishWithResilience` would need ten real failures plus the
 * 5 s breaker delay to reach the same exception, i.e. a slower and timing-dependent way of
 * producing the identical input.
 */
@QuarkusTest
@QuarkusTestResource(CardOutboxDispatchIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.cardissuance.it.PostgresRedisTestResource::class)
class CardOutboxBreakerOpenIT {

    @Inject
    lateinit var repository: CardOutboxRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun persistPending(eventId: UUID, eventType: String) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                val now = Instant.now()
                repository.persist(
                    CardOutboxEntity().apply {
                        this.eventId = eventId
                        this.aggregateId = UUID.randomUUID()
                        this.eventType = eventType
                        this.payload = """{"cardId":"${UUID.randomUUID()}"}"""
                        this.status = OutboxStatus.PENDING.name
                        this.attemptCount = 0
                        this.createdAt = now
                        this.updatedAt = now
                    },
                )
            }
        }
    }

    /** Committed row state, read over plain JDBC — deliberately not through the reactive session. */
    private data class Row(val status: String, val attemptCount: Int, val lastError: String?)

    private fun readRow(eventId: UUID): Row {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(
                "SELECT status, attempt_count, last_error FROM card_outbox WHERE event_id = ?",
            ).use { ps ->
                ps.setObject(1, eventId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no card_outbox row for event_id=$eventId" }
                    return Row(rs.getString(1), rs.getInt(2), rs.getString(3))
                }
            }
        }
    }

    @Test
    fun `a breaker-open fast-fail burns no attempt and never reaches DEAD`() {
        val eventId = UUID.randomUUID()
        persistPending(eventId, "card.issued.v1")

        // Ten ticks — the exact number that took the live rows from PENDING to DEAD.
        repeat(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS) {
            onVertxContext {
                OutboxDispatch.dispatchOnce(repository) {
                    throw CircuitBreakerOpenException(
                        "CardOutboxDispatcher#publishWithResilience circuit breaker is open",
                    )
                }
            }
        }

        val row = readRow(eventId)
        // Before the fix this row was DEAD with attempt_count = 10 and the breaker error stored —
        // terminal, excluded from listProcessable, unreachable by any retry for the rest of time.
        assertThat(row.status).isNotEqualTo(OutboxStatus.DEAD.name)
        assertThat(row.attemptCount).isZero()
        assertThat(row.lastError).isNull()
    }

    @Test
    fun `the row stays reclaimable, so the breaker's half-open probe has work to probe with`() {
        val eventId = UUID.randomUUID()
        persistPending(eventId, "card.status_changed.v1")

        // One outage tick claims the row (PENDING -> DISPATCHING) and abandons it untouched.
        onVertxContext {
            OutboxDispatch.dispatchOnce(repository) {
                throw CircuitBreakerOpenException("circuit breaker is open")
            }
        }
        assertThat(readRow(eventId).status).isNotEqualTo(OutboxStatus.DEAD.name)

        // The breaker closes. The stale-claim reclaim — the same path that recovers a pod which
        // died mid-batch — hands the row straight back. `Duration.ZERO` is the production window
        // (2 min) collapsed so the test does not have to wait it out; the SQL and the transitions
        // are the real repository's.
        val published = mutableListOf<UUID>()
        onVertxContext {
            OutboxDispatch.dispatchOnce(ReclaimImmediately(repository)) { entry ->
                published += entry.eventId
            }
        }
        assertThat(published).contains(eventId)

        val row = readRow(eventId)
        assertThat(row.status).isEqualTo(OutboxStatus.SENT.name)
        // One attempt total: the outage tick contributed nothing.
        assertThat(row.attemptCount).isEqualTo(1)
    }

    /**
     * The real repository with only the stale-claim window shortened, so the reclaim a live pod
     * performs 2 minutes after an abandoned batch happens immediately here. Every statement,
     * including the `FOR UPDATE SKIP LOCKED` claim itself, is [CardOutboxRepositoryImpl]'s.
     */
    private class ReclaimImmediately(private val delegate: CardOutboxRepositoryImpl) : OutboxRepository {
        override suspend fun listProcessable(limit: Int) = delegate.listProcessable(limit)
        override suspend fun claimProcessable(limit: Int, staleAfter: Duration) =
            delegate.claimProcessable(limit, Duration.ZERO)
        override suspend fun countProcessable() = delegate.countProcessable()
        override suspend fun markSent(eventId: UUID, sentAt: Instant) = delegate.markSent(eventId, sentAt)
        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) =
            delegate.markFailed(eventId, error, failedAt)
    }
}
