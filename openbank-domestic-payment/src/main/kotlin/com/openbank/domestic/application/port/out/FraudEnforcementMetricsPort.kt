// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudRejectCause

/** Records fraud-gate outcomes as enums, never as a pass/fail boolean (#4403). */
interface FraudEnforcementMetricsPort {
    /** [decision] is the table's answer; [enforced] says whether it was acted on or only observed. */
    fun recordDecision(decision: FraudGateAction, enforced: Boolean)

    fun recordRejection(cause: FraudRejectCause)
}
