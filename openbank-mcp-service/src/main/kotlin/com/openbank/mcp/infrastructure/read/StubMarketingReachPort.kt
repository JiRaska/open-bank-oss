// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.MarketingReachPort
import jakarta.enterprise.context.ApplicationScoped

/**
 * PHASE 1 deterministic stub behind [MarketingReachPort] (ADR-0209 D5), mirroring [StubProposalPort].
 *
 * It is a stub on purpose rather than an oversight: the real read is
 * `GET /api/v1/consents/grantee/party-service:marketing-comms` on consent-service, which returns the
 * consent ROWS and would have to be counted here. Binding that client is phase 2 and belongs with the
 * charter grant — until a charter carries `query.marketing.readonly`, the OPA PDP denies every call to
 * this tool, so a real client would be unreachable code that nothing could exercise.
 *
 * `phase: 1-stub` is in the response for the same reason `StubProposalPort` carries it: a caller must
 * be able to tell a placeholder from an answer. A stub that returns plausible-looking numbers with no
 * marker is indistinguishable from real reach data, and reach data is what a campaign gets sized on.
 */
@ApplicationScoped
class StubMarketingReachPort(private val mapper: ObjectMapper) : MarketingReachPort {
    override fun countMarketingConsents(): JsonNode = mapper.createObjectNode().apply {
        put("phase", "1-stub")
        put(
            "note",
            "NOT REAL REACH. Phase 2 counts consent-service's grantee view of " +
                "party-service:marketing-comms; do not size a campaign on these numbers.",
        )
        put("grantee", MARKETING_GRANTEE)
        // Counts only — never a party id, an email or a phone number. See MarketingReachPort's kdoc:
        // the aggregate IS the privacy control on this path, not the masker downstream of it.
        putObject("activeByScope").apply {
            put("MARKETING_COMMS_EMAIL", 0)
            put("MARKETING_COMMS_SMS", 0)
            put("MARKETING_COMMS_PUSH", 0)
        }
        put("asOf", null as String?)
    }

    private companion object {
        /** ADR-0205 D3's fixed internal grantee. */
        const val MARKETING_GRANTEE = "party-service:marketing-comms"
    }
}
