// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * The allowlists are the only thing standing between a producer-only topic / unverified lineage
 * edge and a false "not tracked" finding — so the load-bearing contract is the FAILURE direction:
 * no reachable rules.yaml must resolve EMPTY (everything is surfaced), never throw, and never
 * invent entries. When a document IS reachable, its entries suppress exactly the exempted keys.
 */
class GovernanceReadAdapterTest {

    private fun adapterFor(rulesYamlPath: String = "", rulesYamlUrl: String = ""): GovernanceReadAdapter {
        val config = mockk<LivenessSentinelConfig> {
            every { rulesYamlPath() } returns rulesYamlPath.asOptional()
            every { rulesYamlUrl() } returns rulesYamlUrl.asOptional()
        }
        return GovernanceReadAdapter(config)
    }

    private fun String.asOptional(): Optional<String> = if (isBlank()) Optional.empty() else Optional.of(this)

    @Test
    fun `with no source configured the allowlists are empty, so nothing is silently suppressed`(): Unit = runBlocking {
        val adapter = adapterFor()

        assertThat(adapter.eventConsumerAllowlist()).isEmpty()
        assertThat(adapter.lineageAllowlist()).isEmpty()
    }

    @Test
    fun `with a blank path and a blank url the allowlists are empty`(): Unit = runBlocking {
        val adapter = adapterFor(rulesYamlPath = "   ", rulesYamlUrl = " ")

        assertThat(adapter.eventConsumerAllowlist()).isEmpty()
        assertThat(adapter.lineageAllowlist()).isEmpty()
    }

    @Test
    fun `a mounted rules yaml supplies the allowlists`(@TempDir dir: Path): Unit = runBlocking {
        val rules = dir.resolve("rules.yaml")
        Files.writeString(
            rules,
            """
            rules:
              event_consumer_liveness:
                gate: event-consumer-liveness
                allowlist:
                  - topic: "openbank.pid.events"
                    reason: "dead code"
                  - topic: "openbank.loyalty.events"
                    reason: "ADR-0282 phase 2"
              lineage_code_audit:
                gate: lineage-code-audit
                allowlist:
                  - edge: "openbank-copilot-service->openbank-fx-service:api"
                    reason: "shared BFF client"
            """.trimIndent(),
        )
        val adapter = adapterFor(rulesYamlPath = rules.toString())

        assertThat(adapter.eventConsumerAllowlist())
            .containsExactlyInAnyOrder("openbank.pid.events", "openbank.loyalty.events")
        assertThat(adapter.lineageAllowlist())
            .containsExactly("openbank-copilot-service->openbank-fx-service:api")
    }

    @Test
    fun `a configured path that does not exist falls back to empty, not to a failure`(): Unit = runBlocking {
        val adapter = adapterFor(rulesYamlPath = "/nonexistent/rules.yaml", rulesYamlUrl = "")

        assertThat(adapter.eventConsumerAllowlist()).isEmpty()
        assertThat(adapter.lineageAllowlist()).isEmpty()
    }
}
