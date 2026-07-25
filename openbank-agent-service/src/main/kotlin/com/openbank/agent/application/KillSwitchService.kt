// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.`in`.KillSwitchControlUseCase
import com.openbank.agent.application.port.`in`.KillSwitchQueries
import com.openbank.agent.application.port.out.KillSwitchRepository
import com.openbank.agent.domain.control.HaltStatus
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock

/**
 * Kill switch (ADR-0031 D7) — stops an agent without a redeploy. Two layers, runtime wins:
 *
 *  1. **Config baseline** (declarative, GitOps): `agents.yaml` declares per-agent `enabled` and a
 *     `global_controls.kill_switch_enabled`; the runtime mirror is `agent.kill-switch.global-enabled`
 *     + the charter `enabled` flag (CharterRegistry). This is the desired-state default.
 *  2. **Runtime break-glass** (immediate): an authenticated operator flips a row behind
 *     [KillSwitchRepository] via AdminControlResource — no GitOps round-trip, no pod roll. The
 *     sentinel scope `*` halts every agent.
 *
 * [haltReason] is the single pre-flight check (called in AgentChatService before the model is ever
 * touched, next to the rate limiter). Every flip is audited (`agent.killswitch.set`/`.cleared`).
 * Combining the two layers is this service's whole job; the storage half lives in the adapter
 * (ADR-0002 hexagonal) so the JDBC never sits in the application layer.
 */
@ApplicationScoped
class KillSwitchService(
    private val repository: KillSwitchRepository,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) : KillSwitchQueries,
    KillSwitchControlUseCase {

    @Inject
    lateinit var charters: CharterRegistry

    @ConfigProperty(name = "agent.kill-switch.global-enabled", defaultValue = "true")
    var globalEnabled: Boolean = true

    private companion object {
        const val GLOBAL = "*"
    }

    /**
     * Why [agentId] may not run right now, or null when it may. Runtime halts (global, then
     * per-agent) take precedence over the declarative config baseline.
     */
    override fun haltReason(agentId: String): String? {
        repository.findHalt(GLOBAL)?.let { return "all agents are halted: ${it.reason}" }
        repository.findHalt(agentId)?.let { return "agent '$agentId' is halted: ${it.reason}" }
        if (!globalEnabled) return "all agents are disabled (kill-switch config baseline)"
        if (!charters.isEnabled(agentId)) return "agent '$agentId' is disabled (charter config baseline)"
        return null
    }

    /** Suspend a scope (an agent id, or `*` for every agent). Idempotent upsert + audit. */
    override fun halt(scope: String, reason: String, setBy: String) {
        repository.upsertHalt(scope, reason, setBy, clock.instant())
        audit("agent.killswitch.set", scope, setBy, reason)
    }

    /** Lift a runtime halt (the config baseline still applies). Audited even if nothing was set. */
    override fun resume(scope: String, setBy: String) {
        repository.deleteHalt(scope)
        audit("agent.killswitch.cleared", scope, setBy, "resumed")
    }

    /** Every active runtime halt (for the admin status view). */
    override fun listHalts(): List<HaltStatus> = repository.listHalts()

    private fun audit(operation: String, scope: String, setBy: String, reason: String) {
        runBlocking {
            auditPublisher.publish(
                AuditEvent(
                    actorId = setBy,
                    actorType = "HUMAN",
                    operation = operation,
                    resourceType = "agent.killswitch",
                    resourceId = scope,
                    result = AuditResult.SUCCESS,
                    payload = mapOf("scope" to scope, "reason" to reason),
                ),
            )
        }
    }
}
