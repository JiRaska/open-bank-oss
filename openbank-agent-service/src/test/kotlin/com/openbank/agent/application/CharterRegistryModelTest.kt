// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CharterRegistryModelTest {

    private fun registry(entries: Map<String, String>): CharterRegistry {
        val config = mockk<CharterConfig>()
        every { config.charters() } returns entries.map { (id, model) ->
            mockk<CharterConfig.CharterEntry> {
                every { agentId() } returns id
                every { this@mockk.model() } returns model
                every { tokensPerRun() } returns Long.MAX_VALUE
                every { runsPerDay() } returns Long.MAX_VALUE
                every { allowedCapabilities() } returns emptyList()
                every { enabled() } returns true
            }
        }
        return CharterRegistry().also { it.config = config }
    }

    @Test
    fun `modelId returns the charter-declared model for a known agent`() {
        val reg = registry(mapOf("ui-assistant" to "llama-3.3-70b-versatile"))
        assertThat(reg.modelId("ui-assistant")).isEqualTo("llama-3.3-70b-versatile")
    }

    @Test
    fun `modelId returns unknown for an agent with no charter entry`() {
        val reg = registry(emptyMap())
        assertThat(reg.modelId("unregistered-agent")).isEqualTo(CharterRegistry.UNKNOWN_MODEL)
    }

    @Test
    fun `modelId returns unknown when charter declares model as unknown`() {
        val reg = registry(mapOf("mcp-anonymous" to "unknown"))
        assertThat(reg.modelId("mcp-anonymous")).isEqualTo("unknown")
    }
}
