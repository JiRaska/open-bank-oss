// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sca.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxSentRetentionJob
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sca.infrastructure.persistence.repository.ScaOutboxRepositoryImpl
import com.openbank.sca.it.PostgresRedisTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Proves libs-runtime's [OutboxSentRetentionJob] actually purges `sca_outbox` when the SCHEDULER
 * fires it — not when a test calls it. A direct call would supply the Vert.x context the real
 * scheduler must supply itself, so it would pass against a non-`suspend` job that throws
 * `HR000068` on every firing (#2148). The profile shrinks the cron to every two seconds against a
 * real Postgres; the test seeds rows on both sides of the 7-day window and waits for a genuinely
 * scheduler-dispatched run to remove exactly the old SENT ones.
 *
 * sca-service is the subject on purpose: it is a hand-rolled v1 outbox (opt-in via
 * `SentOutboxRetention` on [ScaOutboxRepositoryImpl]), so this also proves the job discovers a v1
 * repository through CDI, not only the kernel base.
 */
@QuarkusTest
@TestProfile(ScaOutboxSentRetentionSchedulerIT.FastRetentionProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaOutboxSentRetentionSchedulerIT {

    /**
     * Literals only — a `QuarkusTestProfile` loads in a different classloader from the test class,
     * so a computed value here would hand the scheduler one thing and the assertion another.
     */
    class FastRetentionProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.retention.cron" to "*/2 * * * * ?",
            "openbank.outbox.retention.sent-days" to "7",
            "openbank.outbox.retention.batch-size" to "2",
            // The dispatcher must not turn the seeded PENDING row into SENT mid-test.
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: ScaOutboxRepositoryImpl

    @Inject
    lateinit var registry: MeterRegistry

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun seed(status: OutboxStatus, sentAt: Instant?, updatedAt: Instant): UUID {
        val msg = OutboxMessage(
            aggregateId = Ids.newId(),
            eventType = "test.event.retention",
            payload = """{"case":"retention"}""",
            createdAt = updatedAt,
        )
        onEventLoop {
            Panache.withTransaction {
                repository.persistInTransaction(msg).chain { _ ->
                    repository.update(
                        "status = ?1, sentAt = ?2, updatedAt = ?3 where eventId = ?4",
                        status.name,
                        sentAt,
                        updatedAt,
                        msg.eventId,
                    )
                }
            }.awaitSuspending()
        }
        return msg.eventId
    }

    private fun exists(eventId: UUID): Boolean =
        onEventLoop { Panache.withSession { repository.count("eventId", eventId) }.awaitSuspending() } > 0

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET.toNanos()
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `a scheduler-dispatched run deletes only SENT rows older than the window`() {
        val now = Instant.now()
        val old = now.minus(Duration.ofDays(30))
        // Three old SENT rows with batch-size 2 force the bounded loop past its first batch.
        val oldSent = (1..3).map { seed(OutboxStatus.SENT, sentAt = old, updatedAt = old) }
        val freshSent = seed(OutboxStatus.SENT, sentAt = now.minus(Duration.ofDays(1)), updatedAt = now)
        val oldPending = seed(OutboxStatus.PENDING, sentAt = null, updatedAt = old)
        val oldDead = seed(OutboxStatus.DEAD, sentAt = null, updatedAt = old)

        assertThat(await { oldSent.none(::exists) })
            .describedAs("the real cron must purge every SENT row older than 7 days within ${BUDGET.seconds}s")
            .isTrue()

        assertThat(exists(freshSent)).describedAs("a SENT row inside the window survives").isTrue()
        assertThat(exists(oldPending)).describedAs("an undelivered row is never purged, however old").isTrue()
        assertThat(exists(oldDead)).describedAs("DEAD rows are the producer-side DLQ, not SENT retention's").isTrue()

        val recorded = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
            .tag("workflow", OutboxSentRetentionJob.WORKFLOW_NAME)
            .gauge()?.value()
        assertThat(recorded).describedAs("a successful scheduled run records the liveness heartbeat").isEqualTo(1.0)
    }

    private companion object {
        val BUDGET: Duration = Duration.ofSeconds(30)
        const val POLL_MILLIS = 250L
    }
}
