// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import com.openbank.casecoordinator.application.port.out.TemporalCaseCancellationPort
import com.openbank.casecoordinator.infrastructure.observability.CaseCoordinatorMetricsService
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class CaseKillSwitchCancellationService(
    private val state: CaseKillSwitchStatePort,
    private val temporal: TemporalCaseCancellationPort,
    private val metrics: CaseCoordinatorMetricsService,
) {
    fun handle(command: KillSwitchCommand) {
        when (command.operation) {
            SET -> halt(command)
            CLEARED -> {
                state.clear(command.scope, command.occurredAt)
                metrics.recordKillSwitchEvent(command.scope, "cleared")
                metrics.setKillSwitchActive(command.scope, false)
            }
        }
    }

    private fun halt(command: KillSwitchCommand) {
        if (command.scope != GLOBAL && command.scope != PILOT_AGENT) return
        state.apply(command)
        metrics.recordKillSwitchEvent(command.scope, "set")
        metrics.setKillSwitchActive(command.scope, true)
        state.cancellableCases().forEach { case ->
            temporal.cancelAndAwait(case.workflowId)
            state.recordHalted(case.workflowId, command)
        }
    }

    fun recordHaltLatency(caseClass: String, deliveryMode: String, openedAt: Instant, haltedAt: Instant) {
        val latencyMs = haltedAt.toEpochMilli() - openedAt.toEpochMilli()
        metrics.recordHaltLatency(caseClass, deliveryMode, latencyMs.coerceAtLeast(0L))
    }

    private companion object {
        const val GLOBAL = "*"
        const val PILOT_AGENT = "rca-investigator"
        const val SET = "agent.killswitch.set"
        const val CLEARED = "agent.killswitch.cleared"
    }
}
