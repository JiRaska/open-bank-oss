// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.temporal

import com.openbank.kyb.application.port.out.BusinessOnboardingWorkflowPort
import com.openbank.kyb.application.workflow.BusinessOnboardingTimersWorkflow
import com.openbank.kyb.domain.model.CaseStatus
import io.quarkus.arc.properties.IfBuildProperty
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Selected at BUILD time by `openbank.temporal.enabled` (lending's #6085 lesson: a runtime env var
 * cannot flip an @IfBuildProperty bean, so the shipped image's default is what decides). When it
 * is off, [NoOpBusinessOnboardingWorkflowPort] is bound and no timer is ever armed — the case still
 * works, it just never expires on its own.
 */
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(1)
@IfBuildProperty(name = "openbank.temporal.enabled", stringValue = "true")
class TemporalBusinessOnboardingWorkflowAdapter(
    private val client: WorkflowClient,
    @param:ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-kyb-onboarding")
    private val taskQueue: String,
    @param:ConfigProperty(name = "openbank.kyb.timers.invitation-ttl-days", defaultValue = "14")
    private val invitationTtlDays: Long,
    @param:ConfigProperty(name = "openbank.kyb.timers.case-ttl-days", defaultValue = "60")
    private val caseTtlDays: Long,
) : BusinessOnboardingWorkflowPort {

    private val log = Logger.getLogger(TemporalBusinessOnboardingWorkflowAdapter::class.java)

    override fun stateEntered(caseId: UUID, state: CaseStatus) {
        val stub = client.newWorkflowStub(
            BusinessOnboardingTimersWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId("kyb-case-timers-$caseId")
                .build(),
        )
        try {
            WorkflowClient.start({ stub.run(caseId, invitationTtlDays, caseTtlDays) })
        } catch (duplicate: WorkflowExecutionAlreadyStarted) {
            log.debugf("kyb case timers already running for %s: %s", caseId, duplicate.message)
        }
        stub.stateEntered(state.name)
    }
}

/** Bound when Temporal is off at build time. Logged once per case transition so the absence is visible in a log, never silent. */
@ApplicationScoped
class NoOpBusinessOnboardingWorkflowPort : BusinessOnboardingWorkflowPort {
    private val log = Logger.getLogger(NoOpBusinessOnboardingWorkflowPort::class.java)

    override fun stateEntered(caseId: UUID, state: CaseStatus) {
        log.debugf("openbank.temporal.enabled=false: no timer armed for kyb case %s in %s", caseId, state)
    }
}
