// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.`in`.MergeRecordQueries
import com.openbank.agent.application.port.`in`.RecordMergeUseCase
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import com.openbank.agent.domain.mergerecord.MergeActorType
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import com.openbank.agent.domain.mergerecord.MergeEvidenceConflict
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * The merge-executor evidence API (ADR-0031 phase 3). A GitHub Actions workflow POSTs every merged
 * agent PR here with a service credential (ROLE_MERGE_RECORDER); the admin-ui BFF and oversight read the
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

    /** The original human-only request; unknown legacy properties remain ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
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

    enum class GitHubAppActorType { GITHUB_APP }

    /** A separate additive contract: App provenance is never parsed as a human merge. */
    data class AppMergeRecordIngestRequest(
        val agentId: String?,
        val prNumber: Long?,
        val prUrl: String?,
        val headBranch: String?,
        val mergedBy: String?,
        val mergedAt: Instant?,
        val title: String?,
        val proposalId: String? = null,
        val source: String? = null,
        val mergedByType: GitHubAppActorType? = null,
        val mergedById: Long? = null,
        val headSha: String? = null,
        val mergeCommitSha: String? = null,
        val authorization: ExistingMergeAuthorization? = null,
        val policyAuthorization: JsonNode? = null,
    )

    data class PolicyAppMergeRecordIngestRequest(
        val agentId: String?,
        val prNumber: Long?,
        val prUrl: String?,
        val headBranch: String?,
        val mergedBy: String?,
        val mergedAt: Instant?,
        val title: String?,
        val proposalId: String? = null,
        val source: String? = null,
        val mergedByType: GitHubAppActorType? = null,
        val mergedById: Long? = null,
        val headSha: String? = null,
        val mergeCommitSha: String? = null,
        val authorization: JsonNode? = null,
        val policyAuthorization: PolicyMergeAuthorization? = null,
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
        val mergedByType: MergeActorType,
        val mergedById: Long?,
        val headSha: String?,
        val mergeCommitSha: String?,
        val authorization: ExistingMergeAuthorization?,
        val policyAuthorization: PolicyMergeAuthorization?,
    )

    private fun AgentMergeRecord.toDto(inserted: Boolean) = MergeRecordDto(
        id = id.toString(), agentId = agentId, prNumber = prNumber, prUrl = prUrl,
        headBranch = headBranch, mergedBy = mergedBy, mergedAt = mergedAt, title = title,
        proposalId = proposalId?.toString(), recordedAt = recordedAt, source = source,
        inserted = inserted,
        mergedByType = mergedByType, mergedById = mergedById, headSha = headSha,
        mergeCommitSha = mergeCommitSha,
        authorization = authorization?.takeUnless {
            it.kind == MergeAuthorizationKind.POLICY_PR_AUTO_MERGE
        }?.toExistingWire(),
        policyAuthorization = authorization?.takeIf {
            it.kind == MergeAuthorizationKind.POLICY_PR_AUTO_MERGE
        }?.toPolicyWire(),
    )

    /**
     * Ingest one merged agent PR. ROLE_MERGE_RECORDER only — a dedicated least-privilege
     * role (ADR-0031 phase 3 hardening): the caller is the CI pipeline's service credential
     * and nothing else, so a full ROLE_ADMIN (an interactive bank administrator role) must
     * NOT be able to write merge evidence. Provisioning the CI client with this role keeps
     * the blast radius of a leaked recorder secret to the merge-evidence API.
     */
    @POST
    @Blocking
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun ingest(body: MergeRecordIngestRequest): Response = store(body.toCommand())

    @POST
    @Path("/app")
    @Blocking
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun ingestApp(body: AppMergeRecordIngestRequest): Response {
        requireNotNull(body.mergedByType) { "mergedByType is required" }
        require(body.policyAuthorization == null) { "policy authorization requires /app-policy" }
        val common = MergeRecordIngestRequest(
            agentId = body.agentId, prNumber = body.prNumber, prUrl = body.prUrl,
            headBranch = body.headBranch, mergedBy = body.mergedBy, mergedAt = body.mergedAt,
            title = body.title, proposalId = body.proposalId, source = body.source,
        )
        return store(
            common.toCommand(
                mergedByType = MergeActorType.GITHUB_APP,
                mergedById = body.mergedById,
                headSha = body.headSha,
                mergeCommitSha = body.mergeCommitSha,
                authorization = body.authorization?.toDomain(),
            ),
        )
    }

    @POST
    @Path("/app-policy")
    @Blocking
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun ingestPolicyApp(body: PolicyAppMergeRecordIngestRequest): Response {
        requireNotNull(body.mergedByType) { "mergedByType is required" }
        require(body.authorization == null) { "authorization families cannot be combined" }
        requireNotNull(body.policyAuthorization) { "policyAuthorization is required" }
        val common = MergeRecordIngestRequest(
            agentId = body.agentId, prNumber = body.prNumber, prUrl = body.prUrl,
            headBranch = body.headBranch, mergedBy = body.mergedBy, mergedAt = body.mergedAt,
            title = body.title, proposalId = body.proposalId, source = body.source,
        )
        return store(
            common.toCommand(
                mergedByType = MergeActorType.GITHUB_APP,
                mergedById = body.mergedById,
                headSha = body.headSha,
                mergeCommitSha = body.mergeCommitSha,
                authorization = body.policyAuthorization.toDomain(),
            ),
        )
    }

    private fun MergeRecordIngestRequest.toCommand(
        mergedByType: MergeActorType = MergeActorType.HUMAN,
        mergedById: Long? = null,
        headSha: String? = null,
        mergeCommitSha: String? = null,
        authorization: MergeAuthorization? = null,
    ): MergeRecordCommand {
        val agentId = requireNotBlank(this.agentId, "agentId")
        val prUrl = requireNotBlank(this.prUrl, "prUrl")
        val headBranch = requireNotBlank(this.headBranch, "headBranch")
        val mergedBy = requireNotBlank(this.mergedBy, "mergedBy")
        val title = requireNotBlank(this.title, "title")
        val mergedAt = requireNotNull(this.mergedAt) { "mergedAt is required" }
        val prNumber = requireNotNull(this.prNumber) { "prNumber is required" }
        val proposalId = this.proposalId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw IllegalArgumentException("proposalId must be a UUID")
        }
        return MergeRecordCommand(
            agentId = agentId,
            prNumber = prNumber,
            prUrl = prUrl,
            headBranch = headBranch,
            mergedBy = mergedBy,
            mergedAt = mergedAt,
            title = title,
            proposalId = proposalId,
            source = this.source?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown",
            mergedByType = mergedByType,
            mergedById = mergedById,
            headSha = headSha,
            mergeCommitSha = mergeCommitSha,
            authorization = authorization,
        )
    }

    private fun store(command: MergeRecordCommand): Response = try {
        val outcome = recordMerge.record(command)
        Response.ok(outcome.record.toDto(outcome.inserted)).build()
    } catch (e: MergeEvidenceConflict) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }

    /** Producer preflight prevents a v1 deployment from silently ignoring App provenance. */
    @GET
    @Path("/capabilities")
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun capabilities() = MergeEvidenceCapabilities()

    data class MergeEvidenceCapabilities(val evidenceSchemaVersion: Int = 2, val policyPrAuthorization: Boolean = true)

    /** Read back the durable record, rather than trusting only a successful POST response. */
    @GET
    @Path("/{prNumber}")
    @Blocking
    @RolesAllowed("ROLE_MERGE_RECORDER")
    fun find(@PathParam("prNumber") prNumber: Long): Response {
        require(prNumber > 0) { "prNumber must be positive" }
        val record = queries.findByPrNumber(prNumber) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(record.toDto(inserted = false)).build()
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
