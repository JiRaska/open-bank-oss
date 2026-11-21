// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.charter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.fetchbroker.application.port.out.CharterHostsPort
import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.HostGrant
import com.openbank.fetchbroker.domain.RenderMode
import org.jboss.logging.Logger

/**
 * Reads `data_scope.public_web.hosts` from the packaged agents.yaml (ADR-0387 D1). Deny-by-default:
 * a charter that is disabled, does not allow `read.public_web`, or lists a host that is not an
 * exact DNS name grants nothing for that entry; an unreadable file grants nothing at all.
 * Entries are a hostname string or `{host, render: static|js}`.
 */
class AgentsYamlCharterHosts(yaml: String?) : CharterHostsPort {
    private val log = Logger.getLogger(AgentsYamlCharterHosts::class.java)
    private val byAgent: Map<String, Map<String, HostGrant>> = parse(yaml)

    override fun grantsFor(agentId: String): Map<String, HostGrant> =
        byAgent[agentId.removePrefix(AGENT_PREFIX)].orEmpty()

    @Suppress("TooGenericExceptionCaught")
    private fun parse(yaml: String?): Map<String, Map<String, HostGrant>> {
        if (yaml == null) {
            log.warn("agent charters unavailable — every web.fetch is denied")
            return emptyMap()
        }
        return try {
            ObjectMapper(YAMLFactory()).readTree(yaml).path("agents")
                .filter { it.path("enabled").asBoolean(false) }
                .filter { a -> a.path("tools").path("allow").any(::grantsPublicWeb) }
                .associate { a ->
                    a.path("id").asText() to grants(a.path("data_scope").path("public_web").path("hosts"))
                }
                .filterValues { it.isNotEmpty() }
        } catch (ex: Exception) {
            log.warnf("agent charters unparseable (%s) — every web.fetch is denied", ex.message)
            emptyMap()
        }
    }

    /**
     * A charter grants the resource either as a flat string entry (`allow: [read.public_web]`) or,
     * the shape every control-plane charter in agents.yaml uses, as an object
     * `{tier: read, resources: [read.public_web]}`. Reading only the string form made every real
     * charter grant nothing here (ADR-0387 S4, regulatory-obligations-watcher). Exact names only:
     * the policy, not this re-check, interprets globs.
     */
    private fun grantsPublicWeb(entry: JsonNode): Boolean =
        if (entry.isTextual) {
            entry.asText() == RESOURCE
        } else {
            entry.path("tier").asText() == TIER_READ && entry.path("resources").any { it.asText() == RESOURCE }
        }

    private fun grants(hosts: JsonNode): Map<String, HostGrant> = hosts.mapNotNull { entry ->
        val host = HostAllowlist.normalise(if (entry.isTextual) entry.asText() else entry.path("host").asText(""))
        val render = when (entry.path("render").asText("static").lowercase()) {
            "static" -> RenderMode.STATIC
            "js" -> RenderMode.JS
            else -> null
        }
        if (render == null || !HostAllowlist.isListableHost(host)) {
            log.warnf("ignoring invalid public_web host entry %s", entry.toString().take(MAX_LOGGED))
            null
        } else {
            HostGrant(host, render)
        }
    }.associateBy { it.host }

    companion object {
        const val RESOURCE = "read.public_web"
        private const val TIER_READ = "read"
        private const val AGENT_PREFIX = "agent:"
        private const val MAX_LOGGED = 120

        fun fromClasspath(path: String): AgentsYamlCharterHosts = AgentsYamlCharterHosts(
            runCatching {
                Thread.currentThread().contextClassLoader.getResourceAsStream(path)?.use {
                    it.readBytes()
                }?.decodeToString()
            }.getOrNull(),
        )
    }
}
