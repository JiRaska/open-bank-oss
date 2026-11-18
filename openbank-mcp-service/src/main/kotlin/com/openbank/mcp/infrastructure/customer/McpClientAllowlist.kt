// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URI
import java.net.URISyntaxException

/**
 * The MCP client allowlist as mcp-service reads it (ADR-0385 D1: "one declaration drives both
 * mcp-service and Keycloak"). Only the fields admission needs are read here; the realm-side fields
 * (redirects, auth method, key-set hash) are the realm-parity gate's business.
 *
 * The tier comes from THIS declaration, keyed by `azp`, never from a token claim.
 */
data class McpClientAllowlist(
    val resource: String,
    val audience: String,
    val clients: Map<String, Client>,
) {
    data class Client(val clientId: String, val tier: Tier, val enabled: Boolean)

    /** `H` hosted confidential, `L` local public (ADR-0385 D1). */
    enum class Tier { H, L }

    /** An admitted client: declared AND enabled. A declared-but-disabled client is not admitted. */
    fun admitted(azp: String?): Client? = azp?.let { clients[it] }?.takeIf { it.enabled }

    companion object {
        private const val SUPPORTED_SCHEMA = 1

        /**
         * Parses the YAML text. Throws [IllegalArgumentException] on anything malformed — the
         * caller turns that into a refusal to boot, never into an empty allowlist.
         */
        fun parse(text: String): McpClientAllowlist {
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *>
                ?: throw IllegalArgumentException("allowlist is not a YAML mapping")
            val schema = root["schema_version"]
            require(schema == SUPPORTED_SCHEMA) { "unsupported allowlist schema_version: $schema" }
            val resource = requiredString(root, "resource")
            require(isHttpsAbsolute(resource)) { "resource must be an absolute https URI: $resource" }
            val audience = requiredString(root, "audience")
            val rawClients = root["clients"] as? List<*>
                ?: throw IllegalArgumentException("allowlist has no 'clients' list")
            val clients = rawClients.map { parseClient(it) }
            val duplicates = clients.groupBy { it.clientId }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate client_id in allowlist: $duplicates" }
            return McpClientAllowlist(resource, audience, clients.associateBy { it.clientId })
        }

        private fun parseClient(raw: Any?): Client {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("allowlist client entry is not a mapping")
            val clientId = requiredString(map, "client_id")
            val tierText = requiredString(map, "tier")
            val tier = Tier.entries.firstOrNull { it.name == tierText }
                ?: throw IllegalArgumentException("client '$clientId' has unknown tier '$tierText'")
            val enabled = map["enabled"] as? Boolean
                ?: throw IllegalArgumentException("client '$clientId' must declare 'enabled' as a boolean")
            return Client(clientId, tier, enabled)
        }

        private fun requiredString(map: Map<*, *>, key: String): String =
            (map[key] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("allowlist is missing '$key'")

        private fun isHttpsAbsolute(uri: String): Boolean = try {
            val parsed = URI(uri)
            parsed.scheme == "https" && !parsed.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
    }
}
