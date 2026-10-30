// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/** Outcome of the in-process charter check. Deny-by-default: anything unexpected is a [Denied]. */
sealed interface CharterGrant {
    data object Granted : CharterGrant

    data class Denied(val reason: String) : CharterGrant
}

/**
 * ADR-0031 D9 phase 3, layer 1 of the PR-path policy gate: the fail-closed, in-process charter
 * check that does NOT depend on the OPA sidecar being up. It re-verifies, against the packaged
 * copy of `openbank-libs/governance/agents.yaml` (staged onto the classpath by `processResources`,
 * derived — never hand-edited), that the `flaky-test-hunter` charter:
 *
 *  1. exists and is `enabled`,
 *  2. has a `tools.allow` entry of exactly `{tier: write_proposal, resources: [... "github-pr" ...]}`,
 *  3. has no `tools.deny` entry matching the same (tier, resource) pair — an explicit deny beats
 *     an explicit grant, mirroring `agents.rego`'s decision shape.
 *
 * A missing resource, an unparseable document, or an absent/tampered entry all DENY — a pentest
 * prompt injection or a broken build can then never re-open the PR path by merely taking the
 * sidecar down; it would have to tamper with the packaged artifact itself. Mirrors
 * `openbank-agent-service`'s `CharterRegistry` allow-list (ADR-0080 P0) as the local fail-safe
 * next to the remote PDP, per the two-layer pattern the MCP gate already uses.
 */
@ApplicationScoped
class AgentCharterGrantVerifier {

    private val log = Logger.getLogger(AgentCharterGrantVerifier::class.java)

    private val mapper = ObjectMapper(YAMLFactory())

    /** Visible for testing: lets a test point the verifier at a fixture or a nonexistent path. */
    internal var resourcePath: String = DEFAULT_RESOURCE_PATH

    fun checkPrOpenGrant(): CharterGrant {
        val yaml = runCatching {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }?.decodeToString()
        }.getOrNull()
        if (yaml == null) {
            log.warnf(
                "Charter resource %s missing or unreadable — denying %s",
                resourcePath,
                PrPathGateVocabulary.TOOL_GH_PR_OPEN,
            )
            return CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_UNAVAILABLE)
        }
        return parse(yaml)
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun parse(yaml: String): CharterGrant = try {
        val charter = mapper.readTree(yaml).path(AGENTS_NODE).firstOrNull {
            it.path(ID_NODE).asText() == PrPathGateVocabulary.AGENT_ID
        }
        when {
            charter == null -> CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED)
            !charter.path(ENABLED_NODE).isBoolean || !charter.path(ENABLED_NODE).booleanValue() ->
                CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED)
            tierResourceDenied(charter.path(TOOLS_NODE).path(DENY_NODE)) ->
                CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED)
            tierResourceGranted(charter.path(TOOLS_NODE).path(ALLOW_NODE)) -> CharterGrant.Granted
            else -> CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED)
        }
    } catch (ex: Exception) {
        log.warnf(
            "Charter resource %s unparseable (%s) — denying %s",
            resourcePath,
            ex.message,
            PrPathGateVocabulary.TOOL_GH_PR_OPEN,
        )
        CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_UNAVAILABLE)
    }

    private fun tierResourceGranted(entries: JsonNode): Boolean = entries.any(::grantsPrOpen)

    private fun tierResourceDenied(entries: JsonNode): Boolean = entries.any(::grantsPrOpen)

    private fun grantsPrOpen(entry: JsonNode): Boolean =
        entry.path(TIER_NODE).asText() == PrPathGateVocabulary.TIER_WRITE_PROPOSAL &&
            entry.path(RESOURCES_NODE).any(::namesGithubPr)

    private fun namesGithubPr(resource: JsonNode): Boolean =
        resource.asText() == PrPathGateVocabulary.RESOURCE_GITHUB_PR || resource.asText() == WILDCARD

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
