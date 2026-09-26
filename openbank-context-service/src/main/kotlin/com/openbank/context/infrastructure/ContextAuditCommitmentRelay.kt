// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Relays only commitments. A crashed claim is retried with the same audit ID. */
@ApplicationScoped
class ContextAuditCommitmentRelay(
    private val sessions: Mutiny.SessionFactory,
    private val dispatchSettings: ContextCommitmentDispatchSettings,
    @Channel("context-audit-commitments-out") private val emitter: Instance<MutinyEmitter<String>>,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.context.audit-export.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @Inject lateinit var outcomeWriter: ContextCommitmentOutcomeWriter

    private val bankScope: String get() = dispatchSettings.bankScope

    private val pending = AtomicLong(0).also { meters.gauge("openbank_context_audit_outbox_pending", it) }
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "1s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = Scheduled.ApplicationNotRunning::class,
    )
    suspend fun dispatch() {
        if (!enabled) return
        refreshPending()
        val outcomes = ArrayList<ContextCommitmentOutcome>(ContextCommitmentOutcomeWriter.MAX_BATCH_SIZE)
        for (row in claim()) {
            val claimToken = checkNotNull(row.claimToken) { "Commitment claim has no ownership token" }
            val status = publish(row)
            outcomes.add(ContextCommitmentOutcome(row.auditId, claimToken, status, clock.instant()))
            if (outcomes.size == ContextCommitmentOutcomeWriter.MAX_BATCH_SIZE) {
                outcomeWriter.persist(ContextCommitmentKind.AUDIT, outcomes)
                outcomes.clear()
            }
        }
        if (outcomes.isNotEmpty()) outcomeWriter.persist(ContextCommitmentKind.AUDIT, outcomes)
        refreshPending()
        if (pending.get() == 0L) liveness?.recordSuccess()
    }

    private suspend fun publish(row: ContextAuditCommitmentOutboxEntity): ContextCommitmentOutcomeStatus = try {
        val eventType = "CONTEXT_READ_AUDIT_COMMITTED"
        val payload = mapper.writeValueAsString(
            mapOf(
                "schemaVersion" to ContextAuditCommitment.SCHEMA_VERSION,
                "eventId" to row.auditId.toString(),
                "eventType" to eventType,
                "aggregateType" to "CONTEXT_READ_AUDIT",
                "aggregateId" to row.auditId.toString(),
                "sourceService" to "context-service",
                "occurredAt" to row.occurredAt.toString(),
                "commitment" to row.commitment,
            ),
        )
        val metadata = OutgoingKafkaRecordMetadata.builder<String>().withKey(row.auditId.toString()).build()
        emitter.get().sendMessage(Message.of(payload).addMetadata(metadata)).awaitSuspending()
        ContextCommitmentOutcomeStatus.SENT
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Publication failures are retryable; persistence failures never enter this branch.
        meters.counter("openbank_context_audit_outbox_publish_failures_total").increment()
        ContextCommitmentOutcomeStatus.FAILED
    }

    private suspend fun refreshPending() {
        val count = sessions.withTransaction { session, _ ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bankScope).singleResult.flatMap {
                    session.createQuery(
                        "select count(o) from ContextAuditCommitmentOutboxEntity o " +
                            "where o.bankScope = :bank and o.status <> 'SENT'",
                        java.lang.Long::class.java,
                    ).setParameter("bank", bankScope).singleResult
                }
        }.awaitSuspending()
        pending.set(count.toLong())
    }

    private suspend fun claim(): List<ContextAuditCommitmentOutboxEntity> {
        val now = clock.instant()
        val stale = now.minus(Duration.ofMinutes(2))
        val retryBefore = now.minus(RETRY_DELAY)
        val rows = sessions.withTransaction { session, _ ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bankScope).singleResult.flatMap {
                    session.createNativeQuery(CLAIM_SQL, ContextAuditCommitmentOutboxEntity::class.java)
                        .setParameter("bank", bankScope)
                        .setParameter("now", now)
                        .setParameter("claimToken", UUID.randomUUID())
                        .setParameter("stale", stale)
                        .setParameter("retryBefore", retryBefore)
                        .setParameter("batchSize", dispatchSettings.batchSize)
                        .resultList
                }
        }.awaitSuspending()
        return rows.map { it as ContextAuditCommitmentOutboxEntity }
    }

    companion object {
        private const val WORKFLOW_NAME = "context-audit-commitment-relay"
        private const val POLL_INTERVAL_SECONDS = 1L
        private val EXPECTED_INTERVAL = Duration.ofSeconds(POLL_INTERVAL_SECONDS)
        private const val RETRY_DELAY_SECONDS = 30L
        private val RETRY_DELAY = Duration.ofSeconds(RETRY_DELAY_SECONDS)
        private const val CLAIM_SQL = """
            UPDATE context_audit_commitment_outbox
            SET status = 'DISPATCHING', claimed_at = :now, updated_at = :now, claim_token = :claimToken
            WHERE audit_id IN (
                SELECT audit_id FROM context_audit_commitment_outbox
                WHERE bank_scope = :bank AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND updated_at <= :retryBefore)
                    OR (status = 'DISPATCHING' AND claimed_at <= :stale)
                )
                ORDER BY occurred_at, audit_id
                LIMIT :batchSize FOR UPDATE SKIP LOCKED
            )
            RETURNING *
        """
    }
}
