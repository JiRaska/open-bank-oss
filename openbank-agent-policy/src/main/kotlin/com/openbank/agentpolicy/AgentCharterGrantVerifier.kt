// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.jboss.logging.Logger

/** Outcome of the in-process charter check. Deny-by-default: anything unexpected is a [Denied]. */
sealed interface CharterGrant {
    data object Granted : CharterGrant

    data class Denied(val reason: String) : CharterGrant
}

/**
 * ADR-0031 D9 phase 4 (generalised from phase 3's flaky-test-hunter verifier): layer 1 of the
 * policy gate — the fail-closed, in-process charter check that does NOT depend on the OPA
 * sidecar being up. It re-verifies, against the packaged copy of
 * `openbank-libs/governance/agents.yaml` (staged onto the classpath by `processResources`,
 * derived — never hand-edited), that the configured agent's charter:
 *
 *  1. exists and is `enabled`,
 *  2. has a `tools.allow` entry of exactly `{tier: <input.tier>, resources: [... <input.resource> ...]}`,
 *  3. has no `tools.deny` entry matching the same (tier, resource) pair — an explicit deny beats
 *     an explicit grant, mirroring `agents.rego`'s decision shape.
 *
 * A missing resource, an unparseable document, or an absent/tampered entry all DENY — a prompt
 * injection or a broken build can then never re-open the write path by merely taking the
 * sidecar down; it would have to tamper with the packaged artifact itself.
 *
 * The (tier, resource) pair under test comes from the caller's [PolicyDecisionInput] against
 * this verifier's [AgentPolicyConfig.agentId], so one verifier instance serves one agent
 * identity across any number of tool/resource pairs — and a second agent gets its own instance
 * with its own config, not a second copy of this class.
 */
class AgentCharterGrantVerifier(
    private val config: AgentPolicyConfig,
    private val resourcePath: String = DEFAULT_RESOURCE_PATH,
) {

    private val log = Logger.getLogger(AgentCharterGrantVerifier::class.java)

    private val mapper = ObjectMapper(YAMLFactory())

    fun checkGrant(input: PolicyDecisionInput = config.defaultInput()): CharterGrant {
        val yaml = runCatching {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }?.decodeToString()
        }.getOrNull()
        if (yaml == null) {
            log.warnf(
                "Charter resource %s missing or unreadable — denying %s on %s for agent %s",
                resourcePath,
                input.tool,
                input.resource,
                config.agentId,
            )
            return CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_UNAVAILABLE)
        }
        return parse(yaml, input)
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun parse(yaml: String, input: PolicyDecisionInput): CharterGrant = try {
        val charter = mapper.readTree(yaml).path(AGENTS_NODE).firstOrNull {
            it.path(ID_NODE).asText() == config.agentId
        }
        when {
            charter == null -> CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED)
            !charter.path(ENABLED_NODE).asBoolean(true) ->
                CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED)
            tierResourceDenied(charter.path(TOOLS_NODE).path(DENY_NODE), input) ->
                CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED)
            tierResourceGranted(charter.path(TOOLS_NODE).path(ALLOW_NODE), input) -> CharterGrant.Granted
            else -> CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED)
        }
    } catch (ex: Exception) {
        log.warnf(
            "Charter resource %s unparseable (%s) — denying %s on %s for agent %s",
            resourcePath,
            ex.message,
            input.tool,
            input.resource,
            config.agentId,
        )
        CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_UNAVAILABLE)
    }

    private fun tierResourceGranted(entries: JsonNode, input: PolicyDecisionInput): Boolean =
        entries.any { grants(it, input) }

    private fun tierResourceDenied(entries: JsonNode, input: PolicyDecisionInput): Boolean =
        entries.any { grants(it, input) }

    private fun grants(entry: JsonNode, input: PolicyDecisionInput): Boolean =
        entry.path(TIER_NODE).asText() == input.tier &&
            entry.path(RESOURCES_NODE).any { namesResource(it, input.resource) }

    private fun namesResource(resource: JsonNode, resourceId: String): Boolean =
        resource.asText() == resourceId || resource.asText() == WILDCARD

    private companion object {
        const val DEFAULT_RESOURCE_PATH = "governance-charters/agents.yaml"
        const val AGENTS_NODE = "agents"
        const val ID_NODE = "id"
        const val ENABLED_NODE = "enabled"
        const val TOOLS_NODE = "tools"
        const val ALLOW_NODE = "allow"
        const val DENY_NODE = "deny"
        const val TIER_NODE = "tier"
        const val RESOURCES_NODE = "resources"
        const val WILDCARD = "*"
    }
}
