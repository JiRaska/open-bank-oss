// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.integration

import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.kyc.domain.model.ScreeningTrigger
import com.openbank.kyc.domain.model.ScreeningTriggerDiff
import com.openbank.kyc.domain.model.ScreeningTriggerEvents
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import com.openbank.kyc.domain.model.ScreeningTriggerState
import com.openbank.kyc.infrastructure.persistence.KycRepository
import com.openbank.kyc.infrastructure.persistence.repository.ScreeningTriggerRepositoryImpl
import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * kyc_screening_triggers (ADR-0256) against the real Postgres — the repo rule stands: a mocked
 * repository cannot prove the ON CONFLICT dedupe, the state flip, or that the
 * KYC_COORDINATOR_OPEN_FAILED outbox row commits in the SAME transaction as the trigger state
 * (the contract's durability requirement). Rows are read back over plain JDBC where the
 * assertion is about the table.
 *
 * Each repo call runs in its own [onEventLoop] bridge: a reactive Panache repo called from a
 * bare @QuarkusTest thread has no Vert.x context (KycOutboxWriteIT), and one bridge per call is
 * the KycOutboxClaimIT shape that survives sequential suspensions without a thread hop.
 *
 * The outbox dispatcher is switched off for the class so the failure row is not drained (and its
 * Kafka send cannot fail against a broker no test JVM serves) before the assertion observes it.
 */
@QuarkusTest
@TestProfile(ScreeningTriggerRepositoryIT.NoDispatchProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class ScreeningTriggerRepositoryIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var triggerRepository: ScreeningTriggerRepositoryImpl

    @Inject
    lateinit var kycRepository: KycRepository

    @Inject
    lateinit var dataSource: DataSource

    private val now: Instant = Instant.parse("2026-02-10T12:00:00Z")

    private fun pendingTrigger(partyId: UUID, publicationId: UUID, chunkIndex: Long = 0) = ScreeningTrigger.pending(
        partyId = partyId,
        publicationId = publicationId,
        chunkIndex = chunkIndex,
        listType = "EU_CONSOLIDATED",
        diff = ScreeningTriggerDiff(
            changedExternalIds = listOf("ext-1", "ext-2"),
            deactivatedExternalIds = listOf("ext-9"),
            changeCount = 3,
        ),
        now = now,
    )

    @Test
    fun `insertIfAbsent dedupes on publication and party`() {
        val publicationId = UUID.randomUUID()
        val partyId = UUID.randomUUID()

        val first = insert(pendingTrigger(partyId, publicationId))
        // Same (publication, party) — a redelivery or a sibling chunk of the same publication.
        val second = insert(pendingTrigger(partyId, publicationId, chunkIndex = 1))
        // A different publication for the same party is a new trigger.
        val third = insert(pendingTrigger(partyId, UUID.randomUUID()))

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(third).isTrue()
    }

    @Test
    fun `recordOpened flips the state and stores the coordinator case id`() {
        val trigger = pendingTrigger(UUID.randomUUID(), UUID.randomUUID())
        insert(trigger)

        onEventLoop { triggerRepository.recordOpened(trigger, "case-123", now) }

        val reloaded = findTrigger(trigger.triggerId)
        assertThat(reloaded.state).isEqualTo(ScreeningTriggerState.OPENED)
        assertThat(reloaded.coordinatorCaseId).isEqualTo("case-123")
        assertThat(reloaded.resolvedAt).isEqualTo(now)
    }

    @Test
    fun `recordOpenFailed flips the state and writes the failure outbox row in the same transaction`() {
        val trigger = pendingTrigger(UUID.randomUUID(), UUID.randomUUID())
        insert(trigger)
        val nextAttempt = now.plusSeconds(300)

        onEventLoop {
            triggerRepository.recordOpenFailed(
                trigger,
                ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE,
                attempts = 1,
                nextAttemptAt = nextAttempt,
                at = now,
            )
        }

        val reloaded = findTrigger(trigger.triggerId)
        assertThat(reloaded.state).isEqualTo(ScreeningTriggerState.OPEN_FAILED)
        assertThat(reloaded.failedReason).isEqualTo(ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE)
        assertThat(reloaded.attempts).isEqualTo(1)
        assertThat(reloaded.nextAttemptAt).isEqualTo(nextAttempt)

        val outbox = outboxRows(trigger.triggerId)
        assertThat(outbox).hasSize(1)
        val (eventType, payload) = outbox.single()
        assertThat(eventType).isEqualTo(ScreeningTriggerEvents.EVENT_COORDINATOR_OPEN_FAILED)
        assertThat(payload).contains("\"triggerId\":\"${trigger.triggerId}\"")
        assertThat(payload).contains("\"partyId\":\"${trigger.partyId}\"")
        assertThat(payload).contains("\"failedReason\":\"COORDINATOR_UNAVAILABLE\"")
        assertThat(payload).contains("\"attempts\":1")
    }

    @Test
    fun `findRetryable returns only failed rows whose backoff has elapsed, parked rows included`() {
        val publicationId = UUID.randomUUID()
        val due = pendingTrigger(UUID.randomUUID(), publicationId)
        val parked = pendingTrigger(UUID.randomUUID(), publicationId)
        val scheduled = pendingTrigger(UUID.randomUUID(), publicationId)
        insert(due)
        insert(parked)
        insert(scheduled)
        onEventLoop {
            triggerRepository.recordOpenFailed(
                due,
                ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE,
                5,
                null,
                now,
            )
        }
        onEventLoop {
            triggerRepository.recordOpenFailed(
                parked,
                ScreeningTriggerFailure.COORDINATOR_REJECTED,
                5,
                null,
                now,
            )
        }
        onEventLoop {
            triggerRepository.recordOpenFailed(
                scheduled,
                ScreeningTriggerFailure.COORDINATOR_UNAVAILABLE,
                1,
                now.plusSeconds(3600),
                now,
            )
        }

        val retryable = findRetryable()

        // `scheduled` is still backing off; `due` and `parked` are due now (null
        // next_attempt_at sorts first). Rows owned by other tests never appear.
        assertThat(retryable.map { it.triggerId }).contains(due.triggerId, parked.triggerId)
        assertThat(retryable.map { it.triggerId }).doesNotContain(scheduled.triggerId)
        assertThat(retryable.filter { it.nextAttemptAt == null }.map { it.triggerId })
            .contains(due.triggerId, parked.triggerId)
    }

    @Test
    fun `the ADR-0256 candidate query returns only parties with a non-terminal case`() {
        val activeParty = UUID.randomUUID()
        val underReviewParty = UUID.randomUUID()
        val approvedParty = UUID.randomUUID()
        onEventLoop { kycRepository.save(caseFor(activeParty, KycCaseStatus.OPEN)) }
        onEventLoop { kycRepository.save(caseFor(underReviewParty, KycCaseStatus.UNDER_REVIEW)) }
        onEventLoop { kycRepository.save(caseFor(approvedParty, KycCaseStatus.APPROVED)) }

        val candidates = onEventLoop { kycRepository.findActivePartyIds(100) }

        assertThat(candidates).contains(activeParty, underReviewParty)
        assertThat(candidates).doesNotContain(approvedParty)
    }

    private fun insert(trigger: ScreeningTrigger): Boolean = onEventLoop { triggerRepository.insertIfAbsent(trigger) }

    private fun findTrigger(triggerId: UUID): ScreeningTrigger = onEventLoop {
        triggerRepository.findByTriggerId(triggerId) ?: error("no trigger row for $triggerId")
    }

    private fun findRetryable(): List<ScreeningTrigger> = onEventLoop {
        triggerRepository.findRetryable(now.plusSeconds(1), limit = 500)
    }

    /**
     * Establishes the Vert.x context a reactive Panache repo needs (KycOutboxClaimIT's helper):
     * a bare @QuarkusTest thread has none, and withTransaction fails at assembly time. One
     * bridge per repo call — a single bridge around several calls resumes on a different thread
     * mid-sequence (HR000068/69) because each bridge runs on its own event-loop context.
     */
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun outboxRows(triggerId: UUID): List<Pair<String, String>> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT event_type, payload FROM kyc_outbox WHERE aggregate_id = ? AND event_type = ?",
        ).use { stmt ->
            stmt.setObject(1, triggerId)
            stmt.setString(2, ScreeningTriggerEvents.EVENT_COORDINATOR_OPEN_FAILED)
            stmt.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
            }
        }
    }

    private fun caseFor(partyId: UUID, status: KycCaseStatus) = KycCase(
        id = UUID.randomUUID(),
        partyId = partyId,
        status = status,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = emptyList(),
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = now.plusSeconds(86_400),
        createdAt = now,
        updatedAt = now,
    )
}
