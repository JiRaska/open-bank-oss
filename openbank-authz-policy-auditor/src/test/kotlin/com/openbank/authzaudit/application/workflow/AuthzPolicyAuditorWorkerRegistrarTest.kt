// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.libs.temporal.TemporalConfig
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.Test

/**
 * With Temporal disabled the registrar must return before it touches the client at all — six
 * services in this fleet failed to boot, or retried past the deadline, because a worker registrar
 * reached for a Temporal frontend that was not there (the API-fuzz harness note in CLAUDE.md).
 * `confirmVerified` on a strict mock is the assertion: any client interaction fails this test.
 */
class AuthzPolicyAuditorWorkerRegistrarTest {

    @Test
    fun `a disabled Temporal skips registration without touching the workflow client`() {
        val temporalConfig = mockk<TemporalConfig>()
        val workflowClient = mockk<WorkflowClient>()
        every { temporalConfig.enabled() } returns false

        AuthzPolicyAuditorWorkerRegistrar(
            temporalConfig,
            workflowClient,
            mockk(),
            mockk(),
            mockk(),
        ).onStart(StartupEvent())

        verify(exactly = 1) { temporalConfig.enabled() }
        // The task queue is not even read on the disabled path.
        confirmVerified(temporalConfig, workflowClient)
    }
}
