// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import jakarta.enterprise.context.ApplicationScoped

/**
 * PHASE 1 deterministic stub behind the proposal port (ADR-0181). `AccountReadPort`'s stub was
 * retired in ADR-0195 step 4 — [com.openbank.mcp.infrastructure.read.RealAccountReadPort] is now
 * the sole implementation. `ProposalPort` stays stubbed: the "PROPOSED row into a maker-checker
 * queue" a real implementation would write turned out to require NEW cross-service API surface
 * (copilot-service's `ActionProposal` domain is internal, not a callable endpoint) — a separate
 * design decision, out of scope for the caller-auth cutover.
 */
@ApplicationScoped
class StubProposalPort(private val mapper: ObjectMapper) : ProposalPort {
    override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode = mapper.createObjectNode()
        .put("phase", "1-stub")
        .put("status", "PROPOSED")
        .put(
            "note",
            "a reviewable proposal; no money moves — HITL + SCA disposes (phase 2 writes the maker-checker row)",
        )
        .put("agentId", consentContext.agentId)
        .set("request", request)
}
