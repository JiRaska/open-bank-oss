// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.lending.origination.OriginationState
import io.smallrye.mutiny.Uni

/**
 * What actually happened to the durable timers for one state entry.
 *
 * This type exists because the previous signature was `Uni<Unit>`, so the offline no-op returned
 * **byte for byte** what the Temporal adapter returns on success, and logged the discard at
 * `debug` — below the shipped level. Nothing anywhere could tell an armed timer from a discarded
 * one (#6085). A no-op, disabled or skipped outcome must never share its signal with a real one;
 * it needs its own value, exactly as `PushResult.outcome` does.
 *
 * A distinct *value* rather than a failure is deliberate here, and differs from the ledger and
 * borrower-credit no-ops of #6057, which refuse. Arming a timer is a **notification**, not the
 * work: every caller invokes it from a `call {}` inside the origination chain, so a failure would
 * make an offline build unable to submit a loan application at all — it would convert a missing
 * control into a total outage of the path the control protects. Reporting `NOT_ARMED` and letting
 * the caller log it loudly keeps the offline build (ADR-0028 D3) working while removing the shared
 * success signal that made the defect invisible.
 */
enum class TimerArmingOutcome {
    /** The durable timers workflow was started/signalled — the wait is now held by Temporal. */
    ARMED,

    /**
     * No workflow backend is bound in this image, so no timer exists for this state entry.
     *
     * In a shipped image this is a defect, not a mode: [com.openbank.lending.infrastructure.adapter
     * .LendingAdapterBindingVerifier] refuses to boot when the runtime configuration asks for
     * Temporal and the no-op is what augmentation baked in.
     */
    NOT_ARMED_NO_WORKFLOW_BACKEND,
}

/**
 * Durable-time notifications for origination (ADR-0211 D2): the application service
 * reports every state entry; the bound adapter (Temporal, or the offline no-op) arms
 * the durable timers — document SLA, offer expiry, reflection/cooling-off wait. The
 * workflow holds NO business state; it only calls back the aggregate's explicit
 * transition commands once a wait elapses.
 */
interface OriginationWorkflowPort {
    fun stateEntered(
        applicationId: LoanApplicationId,
        state: OriginationState,
        reflectionPeriodDays: Int?,
    ): Uni<TimerArmingOutcome>
}
