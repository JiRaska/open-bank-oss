// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.workflow.SettlementWorkflow
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.temporal.TemporalConfig
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val debitPort: DebitPort,
    private val creditPort: CreditPort,
    private val ledgerPort: LedgerPort,
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) : SettlementUseCase {

    @Inject
    constructor(
        settlementRepository: SettlementRepository,
        debitPort: DebitPort,
        creditPort: CreditPort,
        ledgerPort: LedgerPort,
        temporalConfig: TemporalConfig,
        workflowClient: WorkflowClient,
        auditPublisher: AuditEventPublisher,
    ) : this(
        settlementRepository,
        debitPort,
        creditPort,
        ledgerPort,
        temporalConfig,
        workflowClient,
        auditPublisher,
        Clock.systemUTC(),
    )

    private val log = Logger.getLogger(SettlementService::class.java)

    // CodeQL java/log-injection: idempotencyKey is caller-supplied and flows straight into the
    // log line below. Strip CR/LF so an attacker can't forge additional log lines (CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    override suspend fun originate(command: OriginateSettlementCommand): Settlement {
        // Idempotency: derive the settlement id deterministically from the caller's key, so a
        // retried request resolves to the same row (the UUID primary key is the hard duplicate
        // guard even under a concurrent double-submit).
        val id = UUID.nameUUIDFromBytes("settlement:${command.idempotencyKey}".toByteArray())
        val settlement = settlementRepository.findById(id) ?: createPending(command, id)

        // (Re)start the settlement whenever it is not yet terminal: a fresh PENDING, OR an orphaned
        // PENDING left behind if a prior request created the row but failed before/while starting
        // the workflow. settle() is idempotent on the `settlement-$id` workflow id, so a retry
        // recovers the stuck row instead of leaving funds un-moved. Terminal rows are returned as-is.
        if (settlement.status == SettlementStatus.PENDING) {
            settle(settlement.id)
        } else {
            log.infof("Settlement %s already %s; returning without re-settling", id, settlement.status)
        }
        return settlement
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun createPending(command: OriginateSettlementCommand, id: UUID): Settlement {
        val now = Instant.now(clock)
        val settlement = Settlement(
            id = id,
            payerAccountId = command.payerAccountId,
            payeeAccountId = command.payeeAccountId,
            amount = command.amount,
            currency = command.currency,
            status = SettlementStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            settlementRepository.create(settlement).also {
                log.infof("Originated settlement %s (%s %s)", it.id, it.amount, it.currency)
            }
        } catch (ex: Exception) {
            // Lost a concurrent same-key create race: the UUID primary key rejected the duplicate.
            // The winner's row exists — return it (idempotent), don't surface a 500.
            settlementRepository.findById(id)?.also {
                log.infof(
                    "Concurrent create for idempotencyKey '%s' (%s); using the winner's row",
                    command.idempotencyKey.sanitizeForLog(),
                    id,
                )
            } ?: throw ex
        }
    }

    override suspend fun settle(settlementId: UUID): SettlementStatus {
        val settlement = settlementRepository.findById(settlementId)
            ?: error("Settlement $settlementId not found")

        if (temporalConfig.enabled()) {
            val stub = workflowClient.newWorkflowStub(
                SettlementWorkflow::class.java,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(temporalConfig.taskQueue())
                    .setWorkflowId("settlement-$settlementId")
                    // Money-path double-settle guard: a COMPLETED settlement workflow must NOT be
                    // restarted (the default ALLOW_DUPLICATE would, on a status-read/start race,
                    // run a second debit+credit+ledger cycle). FAILED_ONLY rejects a re-start of a
                    // closed-completed run (→ WorkflowExecutionAlreadyStarted, caught below) while
                    // still allowing a genuinely failed run to be retried.
                    .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY,
                    )
                    .build(),
            )
            try {
                WorkflowClient.start(stub::settle, settlementId)
            } catch (alreadyRunning: WorkflowExecutionAlreadyStarted) {
                // Idempotent: the settlement workflow for this id is already in flight (a retry of
                // an orphaned PENDING). Nothing to do — Temporal owns its lifecycle from here.
                log.infof(
                    "Settlement workflow %s already started (%s); idempotent no-op",
                    settlementId,
                    alreadyRunning.message,
                )
            }
            return settlement.status
        }

        return legacySettle(settlementId)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun legacySettle(settlementId: UUID): SettlementStatus {
        // Atomically claim the settlement (PENDING → DEBITED) before moving any money, so two
        // concurrent same-key calls cannot both run the debit. The loser returns the current status.
        if (!settlementRepository.claimForProcessing(settlementId)) {
            val current = settlementRepository.findById(settlementId)?.status
            log.infof("Settlement %s already claimed/settled (%s); skipping legacy settle", settlementId, current)
            return current ?: SettlementStatus.REJECTED
        }
        return runLegacySettle(settlementId)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runLegacySettle(settlementId: UUID): SettlementStatus = try {
        log.infof("Legacy settle: debiting payer for settlement %s (claimed → DEBITED)", settlementId)
        debitPort.debit(settlementId)
        // claimForProcessing already flipped the row to DEBITED (atomic PENDING -> DEBITED CAS)
        // before this method was ever called, so the audited row already carries that status.
        settlementRepository.findById(settlementId)?.let { audit("settlement.debit", it) }

        log.infof("Legacy settle: crediting payee for settlement %s", settlementId)
        creditPort.credit(settlementId)
        val credited = settlementRepository.updateStatus(settlementId, SettlementStatus.CREDITED)
        audit("settlement.credit", credited)

        log.infof("Legacy settle: booking settlement %s to ledger", settlementId)
        ledgerPort.book(settlementId)
        val booked = settlementRepository.updateStatus(settlementId, SettlementStatus.BOOKED)
        audit("settlement.ledger-book", booked)

        SettlementStatus.BOOKED
    } catch (ex: Exception) {
        log.errorf(ex, "Legacy settle failed for settlement %s; rejecting", settlementId)
        val rejected = settlementRepository.updateStatus(settlementId, SettlementStatus.REJECTED)
        audit("settlement.reject", rejected, result = AuditResult.FAILURE)
        SettlementStatus.REJECTED
    }

    /**
     * Publishes an [AuditEvent] for a legacy-path settlement state transition (issue #1502).
     * `actorId`/`actorType` are `settlement-service`/`SERVICE`: the legacy settle path is itself
     * server-driven (originated by an authenticated REST call, but the debit/credit/book sequence
     * that follows carries no per-step caller identity), same convention as the Temporal-activities
     * audit trail in [com.openbank.settlement.application.workflow.SettlementActivitiesImpl].
     */
    private suspend fun audit(operation: String, settlement: Settlement, result: AuditResult = AuditResult.SUCCESS) {
        auditPublisher.publish(
            AuditEvent(
                actorId = "settlement-service",
                actorType = "SERVICE",
                operation = operation,
                resourceType = "settlement",
                resourceId = settlement.id.toString(),
                result = result,
                payload = mapOf(
                    "status" to settlement.status.name,
                    "payerAccountId" to settlement.payerAccountId.toString(),
                    "payeeAccountId" to settlement.payeeAccountId.toString(),
                    "amount" to settlement.amount.toString(),
                    "currency" to settlement.currency,
                ),
            ),
        )
    }
}
