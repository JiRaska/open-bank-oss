// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import jakarta.enterprise.context.ApplicationScoped

/**
 * In-process case capability gate (ADR-0244 D2/D3/D9), deny-by-default. Mirrors the charter's
 * `case_capabilities` in `agents.yaml`: only `case-coordinator` holds `case.open` /
 * `case.coordinate` / `case.synthesize` / `case.preempt`; swarm participants hold join/contribute
 * by being on the chartered roster. This is the fail-safe in-process layer (same role as
 * AgentPolicyGate in agent-service); the OPA bundle adapter that evaluates the same decisions
 * against `case.capabilities` input is Phase 4 scope.
 */
@ApplicationScoped
class CaseCapabilityGate(private val config: CaseCoordinatorConfig) {

    fun canOpenCase(agentId: String): Boolean = agentId in config.case().openAgents()

    fun canJoinCase(agentId: String): Boolean = agentId in config.case().swarmAgents()

    fun canContribute(agentId: String): Boolean = agentId in config.case().swarmAgents()

    fun canPreempt(agentId: String): Boolean = agentId in config.case().openAgents()

    fun canRequestSynthesis(agentId: String): Boolean = agentId in config.case().openAgents()
}
