// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode

/**
 * Makes "an MCP agent can only ever PROPOSE" a property of the CALL PATH rather than of whichever
 * implementation happens to be bound behind
 * [com.openbank.mcp.application.port.out.ProposalPort] (T-E4, #2414).
 *
 * Today the only binding is a deterministic stub that returns the literal string `PROPOSED`, so the
 * guarantee ADR-0031/ADR-0181 states — the model proposes, a human plus SCA disposes, money never
 * moves on the model's word — is currently true only because of a string constant in one class. The
 * day a real port is bound (a maker-checker row, or copilot-service's `ActionProposal`) that
 * constant stops being the guarantee, and the failure mode is the worst kind: silent, because a
 * proposal that came back `EXECUTED` would be serialized to the agent as an ordinary success.
 *
 * So this is a whitelist of exactly one value, applied to EVERY field named `status` anywhere in the
 * returned document — nested included, since an executed payment would most likely arrive embedded
 * in a `payment`/`result` object rather than at the root. A whitelist, not a blacklist of forbidden
 * statuses: a blacklist has to guess the vocabulary a future port will use, and whatever it fails to
 * guess passes. There is no state past PROPOSED that this can accidentally admit.
 *
 * A violation throws, and [com.openbank.mcp.infrastructure.mcp.McpEndpoint] turns any throw from a
 * tool into an audited `tool error` — so the agent learns nothing about the state it must not reach,
 * and the audit trail records the attempt. Fail closed, as everywhere else on this surface.
 *
 * This does NOT decide how a real proposal is persisted or which service owns it; that is the open
 * architecture question in #2414 and stays open. It only guarantees that whatever answers it cannot
 * hand an agent a disposed proposal without this gate going off first.
 */
object ProposedOnly {

    /** The one and only proposal state an MCP tool result may carry. */
    const val PERMITTED_STATUS: String = "PROPOSED"

    /**
     * @return [result] unchanged when every `status` it carries is [PERMITTED_STATUS].
     * @throws IllegalStateException on a missing, non-textual, or non-`PROPOSED` status — including
     *   a nested one — i.e. on any attempt to hand the caller a proposal that has moved past
     *   PROPOSED.
     */
    fun enforce(result: JsonNode): JsonNode {
        val statuses = collectStatuses(result, mutableListOf())
        check(statuses.isNotEmpty()) {
            "proposal result declares no status; a proposal must be explicitly $PERMITTED_STATUS"
        }
        val offending = statuses.filter { it != PERMITTED_STATUS }
        check(offending.isEmpty()) {
            "proposal result carries a status past $PERMITTED_STATUS: ${offending.distinct()} — " +
                "an MCP agent may propose, never dispose"
        }
        return result
    }

    // Every `status` field at any depth, as its raw text (a non-textual status is reported as such
    // rather than skipped, so a numeric or object status cannot slip through unexamined).
    private fun collectStatuses(node: JsonNode, into: MutableList<String>): List<String> {
        if (node.isObject) {
            node.properties().forEach { (name, value) ->
                if (name == STATUS_FIELD) {
                    into.add(if (value.isTextual) value.asText() else "<non-textual:${value.nodeType}>")
                } else {
                    collectStatuses(value, into)
                }
            }
        } else if (node.isArray) {
            node.forEach { collectStatuses(it, into) }
        }
        return into
    }

    private const val STATUS_FIELD = "status"
}
