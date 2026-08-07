// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Loads per-agent charter limits from config (agents.yaml is the canonical doc; these
 * config properties are the runtime projection). D2 (ADR-0031): the gateway enforces
 * `tokens_per_run` and `runs_per_day` declared in the charter so the policy gate can
 * make data-plane decisions without reading YAML at runtime.
 *
 * Designed for extensibility: adding a new agent is a config entry, no code change.
 */
@ApplicationScoped
class CharterRegistry {

    @Inject
    lateinit var config: CharterConfig

    private val log = Logger.getLogger(CharterRegistry::class.java)

    private data class Limits(val tokensPerRun: Long, val runsPerDay: Long)

    private val limits: Map<String, Limits> by lazy {
        config.charters().associate { c ->
            c.agentId() to Limits(
                tokensPerRun = c.tokensPerRun(),
                runsPerDay = c.runsPerDay(),
            )
        }.also { log.infof("charter registry: loaded %d entries", it.size) }
    }

    private val modelIds: Map<String, String> by lazy {
        config.charters().associate { c -> c.agentId() to c.model() }
    }

    /** Charter-declared model id for [agentId] (ADR-0031 D5, issue #3667). Returns [UNKNOWN_MODEL] when undeclared. */
    fun modelId(agentId: String): String = modelIds[agentId] ?: UNKNOWN_MODEL

    companion object {
        const val UNKNOWN_MODEL = "unknown"
    }

    /**
     * Per-agent allow-list of MCP capabilities (ADR-0080 P0). Runtime projection of the
     * `tools.allow` list in agents.yaml. AgentPolicyGate uses it as a fail-safe, in-process
     * deny that does NOT depend on the OPA sidecar: a capability outside this set is denied
     * locally, so a pentest prompt injection can no longer drive a non-charter tool (e.g.
     * aml_list_cases) even while OPA is unreachable. Empty set = no allow-list configured →
     * the gate falls through to the PDP as before (no behaviour change for that agent).
     */
    private val allowedCaps: Map<String, Set<String>> by lazy {
        config.charters().associate { c ->
            c.agentId() to c.allowedCapabilities().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }

    /** Tokens-per-run limit for [agentId], or Long.MAX_VALUE when no charter is configured. */
    fun tokensPerRun(agentId: String): Long = limits[agentId]?.tokensPerRun ?: Long.MAX_VALUE

    /** Runs-per-day limit for [agentId], or Long.MAX_VALUE when no charter is configured. */
    fun runsPerDay(agentId: String): Long = limits[agentId]?.runsPerDay ?: Long.MAX_VALUE

    /** Charter capability allow-list for [agentId]; empty when none is configured. */
    fun allowedCapabilities(agentId: String): Set<String> = allowedCaps[agentId] ?: emptySet()

    private val enabledFlags: Map<String, Boolean> by lazy {
        config.charters().associate { it.agentId() to it.enabled() }
    }

    /**
     * Config-baseline kill switch (ADR-0031 D7): is [agentId] enabled in its charter? An unknown
     * agent defaults to enabled (true) — the gate/charter allow-list still bounds it; this flag is
     * the declarative halt, the runtime KillSwitchService is the immediate override.
     */
    fun isEnabled(agentId: String): Boolean = enabledFlags[agentId] ?: true
}

@ConfigMapping(prefix = "agent.charter")
interface CharterConfig {
    fun charters(): List<CharterEntry>

    interface CharterEntry {
        fun agentId(): String

        /** Maximum tokens consumed in a single chat() invocation (input + output across all turns). */
        @WithDefault("100000")
        fun tokensPerRun(): Long

        /** Maximum chat() invocations per calendar day (UTC). */
        @WithDefault("500")
        fun runsPerDay(): Long

        /**
         * MCP capability allow-list (ADR-0080 P0), mirroring `tools.allow` in agents.yaml.
         * Empty (default) = unconstrained here (PDP decides). When non-empty, the gate denies
         * any capability outside it in-process, independent of the OPA sidecar.
         */
        @WithDefault("")
        fun allowedCapabilities(): List<String>

        /** Config-baseline kill switch (ADR-0031 D7): false halts this agent declaratively. */
        @WithDefault("true")
        fun enabled(): Boolean

        /**
         * Charter-declared LiteLLM model id (ADR-0031 D5, issue #3667). The policy gate records
         * this on every agent.mcp.tool_call audit event so the evidence chain is complete without
         * a model-gateway round-trip at gate time. Defaults to [CharterRegistry.UNKNOWN_MODEL].
         */
        @WithDefault("unknown")
        fun model(): String
    }
}
