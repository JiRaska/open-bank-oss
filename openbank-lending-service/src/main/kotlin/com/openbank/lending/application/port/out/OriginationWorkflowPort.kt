// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.lending.origination.OriginationState
import io.smallrye.mutiny.Uni

/**
 * Durable-time notifications for origination (ADR-0211 D2): the application service
 * reports every state entry; the bound adapter (Temporal, or the offline no-op) arms
 * the durable timers — document SLA, offer expiry, reflection/cooling-off wait. The
 * workflow holds NO business state; it only calls back the aggregate's explicit
 * transition commands once a wait elapses.
 */
interface OriginationWorkflowPort {
    fun stateEntered(applicationId: LoanApplicationId, state: OriginationState, reflectionPeriodDays: Int?): Uni<Unit>
}
