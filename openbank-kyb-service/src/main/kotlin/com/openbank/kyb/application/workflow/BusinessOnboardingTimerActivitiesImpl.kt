// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.workflow

import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.util.UUID

const val TEMPORAL_TIMER_ACTOR = "temporal-timer"

/**
 * Activities run on a Temporal worker thread, which carries no Vert.x context; the use case is
 * reactive-Panache underneath, so the bridge is [runBlocking] here — the same shape lending's
 * `OriginationTimerActivitiesImpl` uses with `await().indefinitely()`. This is NOT a @Scheduled
 * body (the HR000068 trap does not apply): Temporal owns the thread and blocks it by design.
 */
@ApplicationScoped
class BusinessOnboardingTimerActivitiesImpl(
    private val onboarding: BusinessOnboardingUseCase,
    private val meterRegistry: MeterRegistry,
) : BusinessOnboardingTimerActivities {

    private val log = Logger.getLogger(BusinessOnboardingTimerActivitiesImpl::class.java)

    override fun abandonIfInState(caseId: UUID, expectedState: String) {
        val abandoned = runBlocking { onboarding.abandonIfInState(caseId, expectedState, TEMPORAL_TIMER_ACTOR) }
        if (abandoned) {
            meterRegistry.counter(
                "openbank.kyb.cases.abandoned_by_timer",
                "state",
                expectedState,
            ).increment()
        }
    }

    override fun remindPendingSigners(caseId: UUID) {
        // Notification-service delivery is the follow-up; the reminder is observable now so the
        // timer's firing is never a silent no-op (a no-op sharing a signal with success is the
        // fleet's own recorded defect class).
        log.infof("kyb case %s: invitation TTL half elapsed, co-signers still pending (ADR-0284)", caseId)
        meterRegistry.counter("openbank.kyb.cases.signer_reminders").increment()
    }
}
