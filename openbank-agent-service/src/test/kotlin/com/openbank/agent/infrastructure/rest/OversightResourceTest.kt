// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.AgentChatService
import com.openbank.agent.application.OversightService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The manual oversight-sweep button. It must attribute the run as `manual` — the D5 run audit
 * has no other way to tell an operator-triggered sweep from the scheduled cadence — and pass the
 * governed outcome through unchanged.
 */
class OversightResourceTest {

    private val oversight = mockk<OversightService>()
    private val resource = OversightResource().also { it.oversight = oversight }

    @Test
    fun `the sweep is triggered as manual and its outcome is mapped verbatim`() {
        val toolCalls = listOf(AgentChatService.ToolCallRecord("sanctions_list_pending", true, "[]"))
        coEvery { oversight.sweep("manual") } returns AgentChatService.ChatOutcome(
            reply = "nothing pending",
            model = "llama-3.3",
            toolCalls = toolCalls,
            isProposal = true,
        )

        val response = resource.run()

        assertThat(response.reply).isEqualTo("nothing pending")
        assertThat(response.model).isEqualTo("llama-3.3")
        assertThat(response.toolCalls).isEqualTo(toolCalls)
        assertThat(response.isProposal).isTrue()
        coVerify(exactly = 1) { oversight.sweep("manual") }
    }

    @Test
    fun `a sweep that filed no proposal is reported as such, not defaulted to true`() {
        coEvery { oversight.sweep("manual") } returns AgentChatService.ChatOutcome(
            reply = "clean",
            model = "m",
            toolCalls = emptyList(),
        )

        val response = resource.run()

        assertThat(response.isProposal).isFalse()
        assertThat(response.toolCalls).isEmpty()
    }
}
