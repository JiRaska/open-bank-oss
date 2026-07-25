// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.persistence

import com.openbank.agent.application.port.out.KillSwitchRepository
import com.openbank.agent.domain.control.HaltStatus
import jakarta.enterprise.context.ApplicationScoped
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * Plain Agroal JDBC adapter for [KillSwitchRepository] (`agent_kill_switch`, V2 migration).
 * Same reason as [JdbcAgentProposalRepository] for not using Panache: this service cannot run
 * reactive Panache, and the callers are on `@Blocking` worker threads.
 */
@ApplicationScoped
class JdbcKillSwitchRepository(private val dataSource: DataSource) : KillSwitchRepository {

    @Suppress("MagicNumber") // positional JDBC bind indexes
    override fun upsertHalt(scope: String, reason: String, setBy: String, setAt: Instant) {
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
                ps.setTimestamp(4, Timestamp.from(setAt))
                ps.executeUpdate()
            }
        }
    }

    override fun deleteHalt(scope: String) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM agent_kill_switch WHERE scope = ?").use { ps ->
                ps.setString(1, scope)
                ps.executeUpdate()
            }
        }
    }

    override fun findHalt(scope: String): HaltStatus? = query(
        "SELECT scope, reason, set_by, set_at FROM agent_kill_switch WHERE scope = ? AND halted = TRUE",
        scope,
    ).firstOrNull()

    override fun listHalts(): List<HaltStatus> =
        query("SELECT scope, reason, set_by, set_at FROM agent_kill_switch WHERE halted = TRUE ORDER BY set_at DESC")

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
}
