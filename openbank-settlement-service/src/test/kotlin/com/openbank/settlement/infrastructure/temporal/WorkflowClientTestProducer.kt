// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.temporal

import com.openbank.settlement.application.workflow.SettlementWorkflow
import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import java.util.UUID

/**
 * Replaces the real Temporal [WorkflowClient] in @QuarkusTest with an in-process
 * [TestWorkflowEnvironment], so a REST call that originates a settlement (→ `SettlementService.settle`,
 * which is Temporal-only since ADR-0120 Phase 6 / issue #1917) dispatches to a deterministic no-op
 * workflow instead of connecting to a real frontend (which does not exist in the test JVM). Mirrors
 * transaction-service's `WorkflowClientTestProducer`. The worker itself stays off in tests
 * (`openbank.settlement.worker.enabled=false`); this env registers its own no-op worker.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowClientTestProducer {

    private lateinit var testEnv: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        testEnv = TestWorkflowEnvironment.newInstance()
        testEnv.newWorker("openbank-settlement")
            .registerWorkflowImplementationTypes(NoOpSettlementWorkflow::class.java)
        testEnv.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = testEnv.workflowClient

    @PreDestroy
    fun stop() {
        if (::testEnv.isInitialized) testEnv.close()
    }

    class NoOpSettlementWorkflow : SettlementWorkflow {
        override fun settle(settlementId: UUID): SettlementStatus = SettlementStatus.BOOKED
    }
}
