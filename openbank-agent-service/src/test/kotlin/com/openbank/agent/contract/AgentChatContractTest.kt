// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the published contract without booting the app: `info.version` must equal the service
 * version (version.txt), and the spec must document the admin-UI assistant surface (ADR-0031) —
 * the chat + models endpoints and the policy-gated, sensitivity-routed shapes the service returns.
 * Drift fails here.
 */
class AgentChatContractTest {

    private val openapi = File("src/main/resources/openapi.yaml").readText()
    private val applicationYaml = File("src/main/resources/application.yaml").readText()

    @Test
    fun `application yaml has a single top-level quarkus block`() {
        // A duplicate top-level `quarkus:` key silently overrides the first (YAML last-key-wins),
        // which once dropped http.port/oidc/security headers and booted the service on 8080.
        val topLevelQuarkus = applicationYaml.lineSequence().count { it == "quarkus:" }
        assertThat(topLevelQuarkus).isEqualTo(1)
    }

    @Test
    fun `openapi documents a semver contract version`() {
        // ADR-0048: the API-contract version (openapi info.version) and the release
        // version (version.txt) are independent axes and must not be forced equal -
        // release-please bumps version.txt on every release, which used to fail here.
        // The contract axis is classified by the oasdiff CI gate; this test only pins
        // the invariant that the spec declares a parseable semver contract version.
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertThat(version).isNotNull()
        assertThat(version!!).matches("""\d+\.\d+\.\d+.*""")
    }

    @Test
    fun `the assistant endpoints are documented`() {
        assertThat(openapi)
            .contains("/agent/chat:")
            .contains("/agent/models:")
            .contains("operationId: agentChat")
            .contains("operationId: agentModels")
    }

    @Test
    fun `the MCP contract documents the read-only catalog revision review seam`() {
        assertThat(openapi)
            .contains("get_catalog_revision")
            .contains("cannot author, replace, publish or retire")
    }

    @Test
    fun `the oversight sweep endpoint is documented`() {
        assertThat(openapi)
            .contains("/agent/oversight/run:")
            .contains("operationId: agentOversightRun")
            .contains("SweepResponse:")
    }

    @Test
    fun `the chat response documents the policy-gated tool-call record`() {
        val schema = openapi.substringAfter("    ToolCallRecord:")
        assertThat(schema)
            .contains("tool:")
            .contains("allowed:")
            .contains("resultPreview:")
    }

    @Test
    fun `the models response documents the sensitivity routing tier`() {
        val schema = openapi.substringAfter("    ModelInfo:")
        assertThat(schema)
            .contains("provider:")
            .contains("HOSTED")
            .contains("SELF_HOSTED")
    }
}
