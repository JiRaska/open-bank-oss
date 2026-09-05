// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.rest

import com.openbank.flakytest.application.port.incoming.AnalyzeTestIntelligenceUseCase
import com.openbank.flakytest.application.port.incoming.GetFindingsUseCase
import com.openbank.flakytest.application.port.incoming.RunFlakyTestCheckUseCase
import com.openbank.flakytest.domain.model.RunTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FlakyTestResourceTest {

    @Test
    fun `async trigger forwards the bounded idempotency key without waiting for its report`() {
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val findings = mockk<GetFindingsUseCase>()
        val evidence = mockk<AnalyzeTestIntelligenceUseCase>()
        coEvery {
            runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, IDEMPOTENCY_KEY)
        } returns WORKFLOW_ID
        val resource = FlakyTestResource(runCheck, findings, evidence)

        val response = resource.triggerCheckAsyncIdempotent(IDEMPOTENCY_KEY)

        assertThat(response.status).isEqualTo(202)
        assertThat((response.entity as FlakyTestCheckStarted).workflowId).isEqualTo(WORKFLOW_ID)
        coVerify(exactly = 1) { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, IDEMPOTENCY_KEY) }
        coVerify(exactly = 0) { runCheck.run(any()) }
    }

    @Test
    fun `legacy async trigger preserves its current-day service default`() {
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val findings = mockk<GetFindingsUseCase>()
        val evidence = mockk<AnalyzeTestIntelligenceUseCase>()
        coEvery { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, null) } returns WORKFLOW_ID
        val resource = FlakyTestResource(runCheck, findings, evidence)

        val response = resource.triggerCheckAsync()

        assertThat(response.status).isEqualTo(202)
        assertThat((response.entity as FlakyTestCheckStarted).workflowId).isEqualTo(WORKFLOW_ID)
        coVerify(exactly = 1) { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, null) }
    }

    @Test
    fun `idempotent async trigger rejects a missing header before workflow admission`() {
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val findings = mockk<GetFindingsUseCase>()
        val evidence = mockk<AnalyzeTestIntelligenceUseCase>()
        val resource = FlakyTestResource(runCheck, findings, evidence)

        assertThatThrownBy { resource.triggerCheckAsyncIdempotent(null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Idempotency-Key header is required")
        coVerify(exactly = 0) { runCheck.startDetached(any(), any()) }
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "flaky-test-hunter-operator-manual-2026-09-02"
        const val WORKFLOW_ID = "flaky-test-hunter-check-operator_manual-2026-09-02"
    }
}
