// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import jakarta.enterprise.context.ApplicationScoped

/**
 * `propose_payment` has no proposal store behind it, so it REFUSES (T-E4, #2414).
 *
 * This replaces `StubProposalPort`, which answered every call with the literal
 * `{"phase":"1-stub","status":"PROPOSED"}`. That is not an unimplemented control — it is a control
 * that reports success: there is no maker-checker queue, no row, and no human who will ever see the
 * proposal, yet the caller (a model, and through it a person) was told one is awaiting a checker.
 * The `note` field explaining that phase 2 would write the row is not a defence; a model composes a
 * user-facing answer from the `status`, and no client is obliged to relay a note. This repo's
 * standing lesson is that a control which cannot work must refuse rather than fake-enforce
 * (#3613, #3826).
 *
 * Refusing is deliberately NOT the same as removing the tool. The tool stays advertised and stays
 * capability-mapped, so the ADR-0181/ADR-0195 policy path — charter capability, PDP decision, rate
 * limit, argument validation, audit event — keeps being exercised on every attempt, and an attempt
 * to move money on a model's word is still recorded. What changes is only the answer: an explicit
 * refusal that says nothing was recorded, instead of a fabricated acknowledgement.
 *
 * [UnsupportedOperationException] rather than a generic failure so that
 * [com.openbank.mcp.infrastructure.mcp.McpEndpoint] can relay the reason verbatim; every other tool
 * failure stays the opaque "tool error" it is today. The message must therefore contain no customer
 * data — it does not, it is a constant.
 *
 * WHAT THIS DOES NOT DO. It does not close #2414. The `PROPOSED`-only state machine, SCA
 * dynamic-linking at the write boundary, idempotency (T-D2) and the `money_path_services`
 * re-evaluation all still depend on the open architecture decision — copilot-service's
 * `ActionProposal` is an internal domain, not a cross-service endpoint, so which service owns an MCP
 * proposal is undecided. This only stops the surface lying while that decision is pending.
 */
@ApplicationScoped
class UnwiredProposalPort : ProposalPort {

    override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode =
        throw UnsupportedOperationException(REFUSAL)

    private companion object {
        const val REFUSAL: String =
            "propose_payment is unavailable: no proposal store is wired, so no proposal has been " +
                "recorded and no human will review one. Nothing was created — do not report this " +
                "as a submitted payment proposal."
    }
}
