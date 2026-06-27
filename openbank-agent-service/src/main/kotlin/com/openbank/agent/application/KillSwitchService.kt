// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/** A scope is suspended, with who/why/when. */
data class HaltStatus(val scope: String, val reason: String, val setBy: String, val setAt: Instant)

/**
 * Kill switch (ADR-0031 D7) — stops an agent without a redeploy. Two layers, runtime wins:
 *
 *  1. **Config baseline** (declarative, GitOps): `agents.yaml` declares per-agent `enabled` and a
 *     `global_controls.kill_switch_enabled`; the runtime mirror is `agent.kill-switch.global-enabled`
 *     + the charter `enabled` flag (CharterRegistry). This is the desired-state default.
 *  2. **Runtime break-glass** (immediate): an authenticated operator flips a row in
 *     `agent_kill_switch` via AdminControlResource — no GitOps round-trip, no pod roll. The
 *     sentinel scope `*` halts every agent.
 *
 * [haltReason] is the single pre-flight check (called in AgentChatService before the model is ever
 * touched, next to the rate limiter). Every flip is audited (`agent.killswitch.set`/`.cleared`).
 * Plain Agroal JDBC like ProposalService — this service can't run reactive Panache.
 */
@ApplicationScoped
class KillSwitchService(
    private val dataSource: DataSource,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) {

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
    fun haltReason(agentId: String): String? {
        runtimeHalt(GLOBAL)?.let { return "all agents are halted: ${it.reason}" }
        runtimeHalt(agentId)?.let { return "agent '$agentId' is halted: ${it.reason}" }
        if (!globalEnabled) return "all agents are disabled (kill-switch config baseline)"
        if (!charters.isEnabled(agentId)) return "agent '$agentId' is disabled (charter config baseline)"
        return null
    }

    /** Suspend a scope (an agent id, or `*` for every agent). Idempotent upsert + audit. */
    @Suppress("MagicNumber") // positional JDBC bind indexes
    fun halt(scope: String, reason: String, setBy: String) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO agent_kill_switch (scope, halted, reason, set_by, set_at)
                VALUES (?, TRUE, ?, ?, ?)
                ON CONFLICT (scope) DO UPDATE SET halted = TRUE,
                  reason = EXCLUDED.reason, set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, reason)
                ps.setString(3, setBy)
                ps.setTimestamp(4, Timestamp.from(clock.instant()))
                ps.executeUpdate()
            }
        }
        audit("agent.killswitch.set", scope, setBy, reason)
    }

    /** Lift a runtime halt (the config baseline still applies). Audited even if nothing was set. */
    fun resume(scope: String, setBy: String) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM agent_kill_switch WHERE scope = ?").use { ps ->
                ps.setString(1, scope)
                ps.executeUpdate()
            }
        }
        audit("agent.killswitch.cleared", scope, setBy, "resumed")
    }

    /** Every active runtime halt (for the admin status view). */
    fun listHalts(): List<HaltStatus> =
        query("SELECT scope, reason, set_by, set_at FROM agent_kill_switch WHERE halted = TRUE ORDER BY set_at DESC")

    private fun runtimeHalt(scope: String): HaltStatus? = query(
        "SELECT scope, reason, set_by, set_at FROM agent_kill_switch WHERE scope = ? AND halted = TRUE",
        scope,
    ).firstOrNull()

    /** Run a halt-row query (optionally bound to one scope) and map each row to a [HaltStatus]. */
    private fun query(sql: String, scope: String? = null): List<HaltStatus> = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            scope?.let { ps.setString(1, it) }
            ps.executeQuery().use { rs -> rs.toHaltStatuses() }
        }
    }

    private fun ResultSet.toHaltStatuses(): List<HaltStatus> = buildList {
        while (next()) {
            add(
                HaltStatus(
                    scope = getString("scope"),
                    reason = getString("reason"),
                    setBy = getString("set_by"),
                    setAt = getTimestamp("set_at").toInstant(),
                ),
            )
        }
    }

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
