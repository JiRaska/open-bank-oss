// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.messaging

import com.openbank.agent.application.OversightService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The Kafka trigger is disabled by default and must stay inert until GitOps enables it — an
 * accidental sweep runs OPA-gated MCP tools and can file proposals.
 */
class OversightEventConsumerTest {

    private val oversight = mockk<OversightService>()
    private val consumer = OversightEventConsumer().also { it.oversightService = oversight }

    @Test
    fun `a record on a disabled consumer triggers no sweep`() {
        consumer.enabled = false

        consumer.onOversightEvent("{}")

        coVerify(exactly = 0) { oversight.sweep(any()) }
    }

    @Test
    fun `when enabled, the sweep runs and is attributed to the kafka trigger`() {
        consumer.enabled = true
        coEvery { oversight.sweep(any()) } returns mockk()

        consumer.onOversightEvent("{}")

        coVerify(exactly = 1) { oversight.sweep("kafka-event") }
    }
}
