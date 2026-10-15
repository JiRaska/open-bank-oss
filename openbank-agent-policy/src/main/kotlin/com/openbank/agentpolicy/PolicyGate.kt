// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

/**
 * ADR-0031 D9 phase 4: the agent write-path policy gate, generalised out of flaky-test-hunter's
 * phase-3 PrPathPolicyGate. Any service with an agent write path constructs an implementation
 * (typically [AgentPolicyGate]) bound to its own [AgentPolicyConfig] and runs every disposition
 * through [check] BEFORE touching the outside world.
 *
 * Failure contract: implementations of this interface on a WRITE path MUST fail closed — any
 * internal error (charter unreadable, PDP unreachable, unparseable response) is a
 * [PolicyDecision.Denied] with a [AgentPolicyVocabulary] reason, never an exception and never
 * an advisory pass-through. (The deliberate deviation from the agent-service MCP gate, which
 * degrades to ADVISORY on a PDP error, stays in force: that gate fronts READ tools, where
 * fail-closed takes a read-only assistant offline over a sidecar blip; a gate implementing this
 * interface fronts an irreversible write under the agent's identity, so an allow granted while
 * the PDP is down, slow, or spoofed is a code or money-path change that already happened.)
 */
interface PolicyGate {
    suspend fun check(input: PolicyDecisionInput): PolicyDecision
}
