// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import com.openbank.casecoordinator.application.port.out.TemporalCaseCancellationPort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CaseKillSwitchCancellationService(
    private val state: CaseKillSwitchStatePort,
    private val temporal: TemporalCaseCancellationPort,
) {
    fun handle(command: KillSwitchCommand) {
        when (command.operation) {
            SET -> halt(command)
            CLEARED -> state.clear(command.scope, command.occurredAt)
        }
    }

    private fun halt(command: KillSwitchCommand) {
        if (command.scope != GLOBAL && command.scope != PILOT_AGENT) return
        state.apply(command)
        state.cancellableCases().forEach { case ->
            temporal.cancelAndAwait(case.workflowId)
            state.recordHalted(case.workflowId, command)
        }
    }

    private companion object {
        const val GLOBAL = "*"
        const val PILOT_AGENT = "rca-investigator"
        const val SET = "agent.killswitch.set"
        const val CLEARED = "agent.killswitch.cleared"
    }
}
