// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

@WorkflowInterface
interface SettlementWorkflow {
    @WorkflowMethod
    fun settle(settlementId: UUID): SettlementStatus
}
