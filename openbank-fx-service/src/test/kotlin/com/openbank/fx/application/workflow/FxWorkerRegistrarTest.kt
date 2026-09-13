// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.application.workflow

import io.mockk.confirmVerified
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.Test

/**
 * The disable switch is what keeps a Temporal-less environment (CI, the API-fuzz harness) bootable:
 * a registrar that reached for a worker factory anyway would fail the whole StartupEvent. Asserts
 * the switch by EFFECT — the workflow client is never touched — rather than by reading the flag.
 */
class FxWorkerRegistrarTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val activities = mockk<FxActivitiesImpl>()

    @Test
    fun `a disabled registrar never touches the workflow client`() {
        val registrar = FxWorkerRegistrar(
            enabled = false,
            taskQueue = "openbank-fx-conversions",
            workflowClient = workflowClient,
            activities = activities,
        )

        registrar.onStart(StartupEvent())

        // Strict mocks: any call at all on either collaborator would already have thrown above.
        confirmVerified(workflowClient, activities)
    }
}
