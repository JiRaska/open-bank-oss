// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.application.port.out

import java.time.Instant

data class KillSwitchCommand(
    val eventId: String,
    val operation: String,
    val scope: String,
    val reason: String,
    val setBy: String,
    val occurredAt: Instant,
)

data class CancellableCase(val workflowId: String)

data class ActiveKillSwitch(val scope: String, val reason: String, val setBy: String)

interface CaseKillSwitchStatePort {
    fun apply(command: KillSwitchCommand)
    fun clear(scope: String, clearedAt: Instant)
    fun pilotHaltReason(): String?
    fun cancellableCases(): List<CancellableCase>
    fun recordHalted(caseId: String, command: KillSwitchCommand)
    fun activeScopes(): List<ActiveKillSwitch>
}

interface TemporalCaseCancellationPort {
    fun cancelAndAwait(workflowId: String)
}
