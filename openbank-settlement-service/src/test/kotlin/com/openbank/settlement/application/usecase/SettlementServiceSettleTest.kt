// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.settlement.application.port.out.SettlementRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Coverage for [SettlementService.settle]'s not-found guard. The Temporal dispatch path (the sole
 * settlement orchestrator since ADR-0120 Phase 6 / issue #1917 retired the legacy saga) is covered in
 * SettlementServiceTemporalSettleTest against a real in-memory TestWorkflowEnvironment, and the
 * workflow's compensation-on-failure behaviour in SettlementWorkflowImplTest.
 */
class SettlementServiceSettleTest {

    private val repo: SettlementRepository = mockk(relaxed = true)
    private val temporalConfig: TemporalConfig = mockk(relaxed = true)

    @Test
    fun `settle throws when the settlement does not exist`() {
        val workflowClient: WorkflowClient = mockk(relaxed = true)
        val service = SettlementService(repo, temporalConfig, workflowClient, mockk(relaxed = true))
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy { runBlocking { service.settle(id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(id.toString())
    }
}
