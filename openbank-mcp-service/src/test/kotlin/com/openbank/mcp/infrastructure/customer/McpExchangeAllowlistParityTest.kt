// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * The (audience, scope) pairs mcp-service asks for must be exactly the ones the allowlist derives
 * into the realm (ADR-0383 D4). A pair only on this side would be refused by Keycloak; a pair only
 * on the realm side would be a scope mcp-service could request but nothing reviews here.
 */
class McpExchangeAllowlistParityTest {
    @Test
    fun `DownstreamAudience matches the allowlist exchange declaration`() {
        val text = javaClass.classLoader.getResourceAsStream("mcp-client-allowlist.yaml")!!.use {
            String(it.readAllBytes())
        }
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(text)
        val exchange = root["exchange"] as Map<*, *>
        val declared = (exchange["audiences"] as List<*>).map { it as Map<*, *> }
            .associate { it["client_id"] as String to it["scope"] as String }
        assertThat(DownstreamAudience.entries.associate { it.clientId to it.scope }).isEqualTo(declared)
        assertThat((exchange["requester"] as Map<*, *>)["client_id"]).isEqualTo(root["audience"])
    }
}
