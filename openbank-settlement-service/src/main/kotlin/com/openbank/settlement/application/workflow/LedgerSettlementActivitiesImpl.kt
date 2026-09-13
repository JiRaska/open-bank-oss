// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.SettlementCoverPort
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.port.out.SettlementStep
import com.openbank.settlement.application.port.out.SettlementStepOutcome
import com.openbank.settlement.domain.model.SettlementStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.UUID

@ApplicationScoped
open class LedgerSettlementActivitiesImpl(
    private val cover: SettlementCoverPort,
    private val repository: SettlementRepository,
    private val metrics: SettlementMetricsPort,
    private val audit: AuditEventPublisher,
) : LedgerSettlementActivities {
    override fun reserveSettlementCover(id: UUID): Unit = step(SettlementStep.COVER_CHECK) {
        cover.reservePayer(id)
        audit.publish(
            AuditEvent(
                actorId = "settlement-service",
                actorType = "SERVICE",
                operation = "settlement.cover-reserved",
                resourceType = "settlement",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
            ),
        )
    }

    override fun recordProjectionOutcomeUnknown(id: UUID, ledgerStarted: Boolean): SettlementStatus {
        val metric = if (ledgerStarted) SettlementStep.RECORD_LEDGER_UNKNOWN else SettlementStep.RECORD_BALANCE_UNKNOWN
        return step(metric) {
            val requested = if (ledgerStarted) {
                SettlementStatus.LEDGER_STATE_UNKNOWN
            } else {
                SettlementStatus.BALANCE_STATE_UNKNOWN
            }
            val row = repository.recordProjectionUncertainty(id, requested)
            audit.publish(
                AuditEvent(
                    actorId = "settlement-service",
                    actorType = "SERVICE",
                    operation = "settlement.projection-outcome",
                    resourceType = "settlement",
                    resourceId = id.toString(),
                    result = if (row.status == SettlementStatus.BOOKED) AuditResult.SUCCESS else AuditResult.FAILURE,
                    payload = mapOf("status" to row.status.name, "protocol" to row.protocol.name),
                ),
            )
            row.status
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> step(step: SettlementStep, block: suspend () -> T): T = runOnVertxContext {
        try {
            val result = block()
            metrics.sagaStep(step, SettlementStepOutcome.COMPLETED)
            result
        } catch (failure: Throwable) {
            metrics.sagaStep(step, SettlementStepOutcome.FAILED)
            throw failure
        }
    }

    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
