// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.out

/**
 * The verdict of the external policy decision point for one tool call.
 * [reason] is for the audit trail and the log — never for the customer-facing text, which must not
 * hand an attacker a signal to iterate against.
 */
data class ToolPolicyDecision(val allow: Boolean, val reason: String) {
    companion object {
        val ALLOWED = ToolPolicyDecision(allow = true, reason = "allow")

        fun denied(reason: String) = ToolPolicyDecision(allow = false, reason = reason)
    }
}

/**
 * Outbound port: per-call authorization of a copilot tool invocation against the external policy
 * engine (ADR-0089 D3, ADR-0034). Implemented by
 * [com.openbank.copilot.infrastructure.authz.OpaToolGate], which queries the OPA sidecar.
 *
 * **The contract is fail-closed.** An implementation that cannot reach its engine, gets a non-2xx,
 * or cannot parse the answer MUST return [ToolPolicyDecision.denied] — never allow, and never throw
 * a transport exception at the caller. Returning a decision rather than throwing is deliberate: the
 * previous shape threw a JAX-RS `WebApplicationException`, which put an HTTP concern in the middle
 * of a policy contract and made "deny" indistinguishable from a bug at the call site.
 */
interface ToolPolicyPort {

    /**
     * Authorize [toolName] for [customerId]. [amount] rides the policy input for money-path
     * proposals so a per-session ceiling can be enforced centrally.
     */
    fun authorize(toolName: String, customerId: String, amount: String? = null): ToolPolicyDecision
}
