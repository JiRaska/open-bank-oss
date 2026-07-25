// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.`in`

import com.openbank.agent.domain.control.HaltStatus

/**
 * Inbound port: the kill-switch pre-flight every governed run must pass (ADR-0031 D7).
 *
 * Split from [KillSwitchControlUseCase] so the reasoning loop depends only on the *query* — it can
 * ask whether it may run, and cannot lift a halt that was placed on it.
 */
interface KillSwitchQueries {

    /** Why [agentId] may not run right now, or null when it may. Runtime halts beat the config baseline. */
    fun haltReason(agentId: String): String?

    /** Every active runtime halt, for the admin status view. */
    fun listHalts(): List<HaltStatus>
}

/**
 * Inbound port: runtime break-glass control (ADR-0031 D7) — reachable only from the
 * ROLE_ADMIN REST surface, never from an agent.
 */
interface KillSwitchControlUseCase {

    /** Suspend a scope (an agent id, or `*` for every agent). Idempotent, audited. */
    fun halt(scope: String, reason: String, setBy: String)

    /** Lift a runtime halt; the declarative config baseline still applies. Audited either way. */
    fun resume(scope: String, setBy: String)
}
