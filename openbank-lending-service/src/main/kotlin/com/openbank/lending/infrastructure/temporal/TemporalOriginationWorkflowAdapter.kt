// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.temporal

import com.openbank.lending.application.port.out.OriginationWorkflowPort
import com.openbank.lending.application.port.out.TimerArmingOutcome
import com.openbank.lending.application.workflow.OriginationTimersWorkflow
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.lending.origination.OriginationState
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.mutiny.Uni
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Temporal binding of [OriginationWorkflowPort] (ADR-0211 D2): starts the per-
 * application timers workflow on first state entry (duplicate start = already
 * running, which is the normal signal path), then signals every subsequent state.
 * Build-time gated on `openbank.temporal.enabled`; the offline default is the no-op.
 */
// `@Unremovable` because a test asserts this bean's PRESENCE
// (OriginationWorkflowAdapterBindingIT, #6085); unused-bean removal is deliberately NOT disabled,
// so the absence assertion in the inert half has exactly one possible cause: the build-time gate.
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(1)
@IfBuildProperty(name = "openbank.temporal.enabled", stringValue = "true")
class TemporalOriginationWorkflowAdapter(
    private val client: WorkflowClient,
    @param:ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-lending-origination")
    private val taskQueue: String,
    @param:ConfigProperty(name = "lending.origination.timers.offer-validity-days", defaultValue = "30")
    private val offerValidityDays: Long,
    @param:ConfigProperty(name = "lending.origination.timers.docs-sla-days", defaultValue = "14")
    private val docsSlaDays: Long,
) : OriginationWorkflowPort {

    private val log = Logger.getLogger(TemporalOriginationWorkflowAdapter::class.java)

    override fun stateEntered(
        applicationId: LoanApplicationId,
        state: OriginationState,
        reflectionPeriodDays: Int?,
    ): Uni<TimerArmingOutcome> = Uni.createFrom().item {
        val stub = client.newWorkflowStub(
            OriginationTimersWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId("origination-timers-${applicationId.value}")
                .build(),
        )
        try {
            WorkflowClient.start({ stub.run(applicationId.value, offerValidityDays, docsSlaDays) })
        } catch (duplicate: WorkflowExecutionAlreadyStarted) {
            log.debugf("origination timers already running for %s: %s", applicationId.value, duplicate.message)
        }
        stub.stateEntered(state.name, reflectionPeriodDays)
        TimerArmingOutcome.ARMED
    }
}
