// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Image-pinned ownership derived from the same source declarations as the service catalog. */
object BusinessDomainCatalog {
    private val moduleName = Regex("^openbank-[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val labels: Map<String, String> = requireNotNull(
        javaClass.classLoader.getResourceAsStream("business-domains/taxonomy.json"),
    ) { "Canonical business-domain taxonomy is missing from the MCP image" }.use { stream ->
        ObjectMapper().readTree(stream).get("domains").associate { domain ->
            domain.get("id").asText() to domain.get("labelEn").asText()
        }
    }

    /** A multi-service tool has one domain only when every owner is known and agrees. */
    fun forServices(vararg modules: String): String {
        val owners = modules.map(::ownerOf)
        if (owners.isEmpty() || owners.any { it == null }) return "Unclassified"
        return owners.distinct().singleOrNull()?.let(labels::get) ?: "Unclassified"
    }

    private fun ownerOf(module: String): String? {
        if (!moduleName.matches(module)) return null
        return javaClass.classLoader.getResourceAsStream("business-domains/$module/governance.yaml")
            ?.use { stream ->
                val declaration = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(stream)
                declaration["dataDomain"] as? String
            }
    }
}
