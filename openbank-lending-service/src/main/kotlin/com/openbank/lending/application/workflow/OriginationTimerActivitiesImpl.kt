// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.workflow

import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.libs.domain.identifiers.LoanApplicationId
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

const val TEMPORAL_TIMER_ACTOR = "temporal-timer"

/**
 * Activity bindings for [OriginationTimersWorkflow] (ADR-0211 D2). Every activity is
 * idempotent: the state check happens inside the use case against the live aggregate,
 * so a duplicated fire after a Temporal retry can never double-transition.
 */
@ApplicationScoped
class OriginationTimerActivitiesImpl(
    private val useCases: ApplyForLoanUseCase,
    private val meterRegistry: MeterRegistry,
) : OriginationTimerActivities {

    private val log = Logger.getLogger(OriginationTimerActivitiesImpl::class.java)

    override fun expireIfInState(applicationId: UUID, expectedState: String) {
        useCases.expireIfInState(LoanApplicationId(applicationId), expectedState, TEMPORAL_TIMER_ACTOR)
            .await().indefinitely()
    }

    override fun advanceIfInState(applicationId: UUID, expectedState: String) {
        useCases.advanceIfInState(LoanApplicationId(applicationId), expectedState, TEMPORAL_TIMER_ACTOR)
            .await().indefinitely()
    }

    override fun remindDocumentSla(applicationId: UUID) {
        log.warnf(
            "document-collection SLA half elapsed for application %s — operator reminder (ADR-0211 D2)",
            applicationId,
        )
        meterRegistry.counter("lending.origination.docs_sla.reminder").increment()
    }
}
