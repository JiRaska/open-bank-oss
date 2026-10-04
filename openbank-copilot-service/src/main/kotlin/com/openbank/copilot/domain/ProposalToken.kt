// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Durable handle for a money-path action proposal (ADR-0089 D2, Track A) — **not wired** (#5900).
 *
 * NOTHING IN `src/main` ISSUES ONE. This type is declared, stored and read, but no production code
 * path ever creates an instance, so [com.openbank.copilot.application.port.out.ProposalTokenStore]
 * is write-never/read-always and `POST /api/v1/copilot/actions/{tokenId}/confirm` can only answer
 * 404 on a running pod. `ActionConfirmResourceTest` is green because it seeds the store itself — a
 * test that supplies the missing producer cannot detect that the producer is missing.
 *
 * The HITL path that IS live does not use this type. `CopilotChatService` emits a
 * `[PROPOSAL_END:{...}]` sentinel on the SSE stream carrying the validated
 * [ActionProposal] fields, and the app routes those into the EXISTING customer-edge payment + SCA
 * (dynamic-linking) flow — deliberately "without a second HTTP round-trip", which is precisely the
 * round-trip this token would be for. Track A is the alternative to that, and is unbuilt at BOTH
 * ends: there is no mint site on the propose path, and no executor behind the confirm (the endpoint
 * logs, deletes the token and returns a fresh random `actionId` — it moves no money and verifies no
 * SCA assertion). Building only the producer would yield a second half-wired control, not a working
 * one; see #5900 for what a real implementation needs.
 *
 * Declared unavailable, and re-proven every run, by `evals.ProposalPathAvailabilityTest` — that
 * guard goes RED the moment production code constructs this type, which is the signal to promote
 * the eval scenario rather than leave a stale declaration standing.
 *
 * Domain class — no framework imports.
 */
data class ProposalToken(
    val id: UUID,
    val toolName: String,
    val params: Map<String, Any>,
    val expiresAt: Instant,
    val customerId: String,
) {
    companion object {
        private const val LOG_REF_HEX_CHARS = 12

        /**
         * A stable, non-reversible reference to a proposal token for log lines. The token id is a
         * one-time capability (presented by the owning customer, it confirms the action), so it
         * never goes to a log in the clear; this SHA-256 prefix still correlates the store,
         * confirm and policy lines of one proposal.
         */
        fun logRef(id: UUID): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toString().toByteArray(Charsets.UTF_8))
            return "ptk_" + digest.joinToString("") { "%02x".format(it) }.take(LOG_REF_HEX_CHARS)
        }
    }
}
