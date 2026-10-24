// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.`in`.MergeRecordQueries
import com.openbank.agent.application.port.`in`.RecordMergeUseCase
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * The recorded-human-merge API (ADR-0031 phase 3). A GitHub Actions workflow POSTs every merged
 * agent PR here with a service credential (ROLE_ADMIN); the admin-ui BFF and oversight read the
 * evidence back. Ingest is idempotent by pr_number — the webhook can redeliver — and returns the
 * already-stored record on a duplicate, with no second audit event.
 *
 * JAX-RS params are nullable types validated in the body: a non-nullable param on an absent
 * query/body field 500s before any guard runs, so the guards live here and map to 400 through
 * libs-runtime's IllegalArgumentException mapping.
 *
 * @Blocking because the store is imperative JDBC (worker thread), like ProposalResource.
 */
@Path("/api/v1/agent-merge-records")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AgentMergeRecordResource {

    @Inject lateinit var recordMerge: RecordMergeUseCase

    @Inject lateinit var queries: MergeRecordQueries

    data class MergeRecordIngestRequest(
        val agentId: String?,
        val prNumber: Long?,
        val prUrl: String?,
        val headBranch: String?,
        val mergedBy: String?,
        val mergedAt: Instant?,
        val title: String?,
        val proposalId: String? = null,
        val source: String? = null,
    )

    data class MergeRecordDto(
        val id: String,
        val agentId: String,
        val prNumber: Long,
        val prUrl: String,
        val headBranch: String,
        val mergedBy: String,
        val mergedAt: Instant,
        val title: String,
        val proposalId: String?,
        val recordedAt: Instant,
        val source: String,
        /** False on a duplicate redelivery — the stored (original) record, unchanged. */
        val inserted: Boolean,
    )

    private fun AgentMergeRecord.toDto(inserted: Boolean) = MergeRecordDto(
        id = id.toString(), agentId = agentId, prNumber = prNumber, prUrl = prUrl,
        headBranch = headBranch, mergedBy = mergedBy, mergedAt = mergedAt, title = title,
        proposalId = proposalId?.toString(), recordedAt = recordedAt, source = source,
        inserted = inserted,
    )

    /**
     * Ingest one merged agent PR. ROLE_MERGE_RECORDER only — a dedicated least-privilege
     * role (ADR-0031 phase 3 hardening): the caller is the CI pipeline's service credential
     * and nothing else, so a full ROLE_ADMIN (an interactive bank administrator role) must
     * NOT be able to write merge evidence. Provisioning the CI client with this role keeps
     * the blast radius of a leaked recorder secret to one endpoint.
     */
    @POST
    @Blocking
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun ingest(body: MergeRecordIngestRequest): Response {
        val agentId = requireNotBlank(body.agentId, "agentId")
        val prUrl = requireNotBlank(body.prUrl, "prUrl")
        val headBranch = requireNotBlank(body.headBranch, "headBranch")
        val mergedBy = requireNotBlank(body.mergedBy, "mergedBy")
        val title = requireNotBlank(body.title, "title")
        val mergedAt = requireNotNull(body.mergedAt) { "mergedAt is required" }
        val prNumber = requireNotNull(body.prNumber) { "prNumber is required" }
        val proposalId = body.proposalId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw IllegalArgumentException("proposalId must be a UUID")
        }
        val outcome = recordMerge.record(
            MergeRecordCommand(
                agentId = agentId,
                prNumber = prNumber,
                prUrl = prUrl,
                headBranch = headBranch,
                mergedBy = mergedBy,
                mergedAt = mergedAt,
                title = title,
                proposalId = proposalId,
                source = body.source?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown",
            ),
        )
        return Response.ok(outcome.record.toDto(outcome.inserted)).build()
    }

    /** The recorded-merge evidence, newest first; `agentId` narrows to one agent's merges. */
    @GET
    @Blocking
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    fun list(@QueryParam("agentId") agentId: String?): List<MergeRecordDto> =
        queries.list(agentId?.trim()?.takeIf { it.isNotEmpty() }).map { it.toDto(inserted = true) }

    private fun requireNotBlank(value: String?, field: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("$field is required")
}
