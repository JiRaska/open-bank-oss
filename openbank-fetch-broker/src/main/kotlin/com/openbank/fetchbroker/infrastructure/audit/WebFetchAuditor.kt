// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.audit

import com.openbank.fetchbroker.application.port.out.FetchAuditPort
import com.openbank.fetchbroker.domain.FetchEnvelope
import com.openbank.fetchbroker.domain.FetchOutcome
import com.openbank.libs.audit.AuditChannel
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * One AI-attributed audit event per `web.fetch` (ADR-0387 D5), carrying `source_url`, `sha256` and
 * `renderer` so a finding's quote can be checked against the source as it was when read. Same
 * envelope shape as mcp-service's McpCallAuditor.
 */
@ApplicationScoped
class WebFetchAuditor(private val publisher: AuditEventPublisher) : FetchAuditPort {
    override suspend fun fetched(agentId: String, envelope: FetchEnvelope) {
        publisher.publish(
            AuditEvent(
                actorId = agentId,
                actorType = ACTOR_TYPE,
                operation = OPERATION,
                resourceType = RESOURCE_TYPE,
                resourceId = envelope.url,
                timestamp = envelope.fetchedAt,
                result = when (envelope.outcome) {
                    FetchOutcome.FETCHED -> AuditResult.SUCCESS
                    FetchOutcome.DENIED -> AuditResult.DENIED
                    FetchOutcome.NOT_RENDERABLE -> AuditResult.FAILURE
                },
                channel = AuditChannel.MCP,
                payload = buildMap {
                    put("charter", agentId.removePrefix("agent:"))
                    put("tool", TOOL)
                    put("source_url", envelope.url)
                    put("final_url", envelope.finalUrl)
                    put("sha256", envelope.sha256)
                    put("bytes", envelope.bytes)
                    put("renderer", envelope.renderer)
                    put("outcome", envelope.outcome.name)
                    envelope.reason?.let { put("reason", it) }
                },
            ),
        )
    }

    companion object {
        const val ACTOR_TYPE = "AI_AGENT"
        const val OPERATION = "agent.mcp.tool_call"
        const val RESOURCE_TYPE = "public_web"
        const val TOOL = "web.fetch"
    }
}
