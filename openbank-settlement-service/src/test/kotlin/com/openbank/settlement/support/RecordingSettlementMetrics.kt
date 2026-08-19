// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.support

import com.openbank.settlement.application.port.out.OriginateOutcome
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.WorkflowStartOutcome
import com.openbank.settlement.domain.model.SettlementStatus

/**
 * Recording fake for [SettlementMetricsPort]. The port exists so the application layer never sees
 * Micrometer; this is the other half of that — the counters are assertable without a registry.
 *
 * A mock returning Unit would satisfy the compiler and prove nothing. These lists are what let a
 * test say "an idempotent hit was counted as an idempotent hit, not as a fresh create", which is
 * the distinction the port was introduced to preserve.
 */
class RecordingSettlementMetrics : SettlementMetricsPort {
    val transitions = mutableListOf<SettlementStatus>()
    val originations = mutableListOf<OriginateOutcome>()
    val workflowStarts = mutableListOf<WorkflowStartOutcome>()

    override fun recordTransition(status: SettlementStatus) {
        transitions += status
    }
    override fun recordOriginated(outcome: OriginateOutcome) {
        originations += outcome
    }
    override fun recordWorkflowStart(outcome: WorkflowStartOutcome) {
        workflowStarts += outcome
    }
}
