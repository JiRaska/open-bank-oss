// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.application.port.out.OriginationOutcome
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.workflow.LedgerSettlementWorkflow
import com.openbank.settlement.application.workflow.SettlementWorkflow
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val metrics: SettlementMetricsPort,
    private val clock: Clock,
    private val ledgerProjectionEnabled: Boolean = false,
) : SettlementUseCase {

    @Inject
    constructor(
        settlementRepository: SettlementRepository,
        temporalConfig: TemporalConfig,
        workflowClient: WorkflowClient,
        metrics: SettlementMetricsPort,
        @ConfigProperty(name = "openbank.settlement.ledger-projection-enabled", defaultValue = "false")
        ledgerProjectionEnabled: Boolean = false,
    ) : this(
        settlementRepository,
        temporalConfig,
        workflowClient,
        metrics,
        Clock.systemUTC(),
        ledgerProjectionEnabled,
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
        val existing = settlementRepository.findById(id)
        val settlement = existing ?: createPending(command, id)
        // Validate both an existing row and the winner of a concurrent insert before any dispatch.
        require(
            settlement.payerAccountId == command.payerAccountId &&
                settlement.payeeAccountId == command.payeeAccountId &&
                settlement.amount.compareTo(command.amount) == 0 &&
                settlement.currency == command.currency,
        ) { "Idempotency key is already bound to a different settlement instruction" }
        // `created` vs `replayed` on the ACCEPTANCE of a request — nothing has moved yet at this
        // point, so the metric deliberately does not claim a settlement.
        metrics.settlementOriginated(
            settlement.currency,
            if (existing == null) OriginationOutcome.CREATED else OriginationOutcome.REPLAYED,
        )

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
            protocol = if (ledgerProjectionEnabled) {
                SettlementProtocol.LEDGER_PROJECTION
            } else {
                SettlementProtocol.LEGACY
            },
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

        // Persisted at origination: changing the rollout flag cannot change a retry's protocol.
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("settlement-$settlementId")
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .build()
        try {
            when (settlement.protocol) {
                SettlementProtocol.LEGACY -> {
                    val stub = workflowClient.newWorkflowStub(SettlementWorkflow::class.java, options)
                    WorkflowClient.start(stub::settle, settlementId)
                }
                SettlementProtocol.LEDGER_PROJECTION -> {
                    val stub = workflowClient.newWorkflowStub(LedgerSettlementWorkflow::class.java, options)
                    WorkflowClient.start(stub::settleViaLedger, settlementId)
                }
            }
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
}
