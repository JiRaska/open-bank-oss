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
import org.junit.jupiter.api.Test

class FlakyTestResourceTest {

    @Test
    fun `async trigger accepts the workflow without waiting for its report`() {
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val findings = mockk<GetFindingsUseCase>()
        val evidence = mockk<AnalyzeTestIntelligenceUseCase>()
        coEvery { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL) } returns "operator-workflow-1"
        val resource = FlakyTestResource(runCheck, findings, evidence)

        val response = resource.triggerCheckAsync()

        assertThat(response.status).isEqualTo(202)
        assertThat((response.entity as FlakyTestCheckStarted).workflowId).isEqualTo("operator-workflow-1")
        coVerify(exactly = 1) { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL) }
        coVerify(exactly = 0) { runCheck.run(any()) }
    }
}
