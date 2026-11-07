// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudHoldBasis
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudRejectCause
import com.openbank.domestic.application.workflow.FraudReviewDecision

/** Records fraud-gate outcomes as enums, never as a pass/fail boolean (#4403). */
interface FraudEnforcementMetricsPort {
    /** [decision] is the table's answer; [enforced] says whether it was acted on or only observed. */
    fun recordDecision(decision: FraudGateAction, enforced: Boolean)

    fun recordRejection(cause: FraudRejectCause)

    /** An enforced hold was placed. [basis] separates a suspicious verdict from an unscored payment. */
    fun recordHold(basis: FraudHoldBasis)

    /** An analyst decision reached the rail; [delivery] says whether a run was waiting for it. */
    fun recordHoldResolution(decision: FraudReviewDecision, delivery: FraudHoldDelivery)
}
