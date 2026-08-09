// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.application.CaseCapabilityGate
import com.openbank.casecoordinator.application.CaseOpenResult
import com.openbank.casecoordinator.application.CaseOpenService
import com.openbank.casecoordinator.application.CaseThreadService
import com.openbank.casecoordinator.application.workflow.CaseWorkflow
import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseSummary
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import com.openbank.libs.temporal.TemporalConfig
import io.smallrye.common.annotation.Blocking
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Case-coordinator REST surface (ADR-0244): case-open authority (D9), the signal ingress that
 * feeds a running CaseWorkflow, and the Phase 2 read API (#4185) projecting case history into the
 * ADR-0246 thread view. Every capability decision goes through the in-process CaseCapabilityGate
 * (D2); the OPA bundle evaluating the same decisions is Phase 4 scope.
 */
@Path("/api/v1/case-coordinator")
@Produces(MediaType.APPLICATION_JSON)
class CaseCoordinatorResource(
    private val openService: CaseOpenService,
    private val threadService: CaseThreadService,
    private val gate: CaseCapabilityGate,
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
) {

    data class Status(val service: String, val status: String)

    data class CaseListResponse(val cases: List<CaseSummary>)

    data class OpenCaseRequest(
        val caseClass: String? = null,
        val subjectRef: String? = null,
        val openedBy: String? = null,
        val dispositionTarget: String? = null,
    )

    data class OpenCaseResponse(val caseId: String)

    data class SignalRequest(
        val type: String? = null,
        val agentId: String? = null,
        val role: String? = null,
        val summary: String? = null,
        val evidenceRefs: List<String>? = null,
        val contested: Boolean? = null,
        val newEvidenceRef: String? = null,
        val reason: String? = null,
    )

    @GET
    @Path("/status")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    fun status(): Status = Status(service = "case-coordinator-agent", status = "up")

    @GET
    @Path("/cases")
    @Blocking
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER", "ROLE_OPERATOR")
    fun listCases(
        @QueryParam("status") status: String?,
        @DefaultValue("50") @QueryParam("limit") limit: Int,
    ): CaseListResponse {
        val boundedLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        return CaseListResponse(threadService.listCases(status?.uppercase(), boundedLimit))
    }

    @GET
    @Path("/cases/{caseId}")
    @Blocking
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER", "ROLE_OPERATOR")
    fun caseThread(@PathParam("caseId") caseId: String?): Response {
        val id = requireNotNull(caseId) { "caseId path parameter is required" }
        val thread = threadService.caseThread(id)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(errorBody("no case with id '$id'")).build()
        return Response.ok(thread).build()
    }

    @POST
    @Path("/cases")
    @RolesAllowed("ROLE_ADMIN", "ROLE_OPERATOR")
    fun openCase(request: OpenCaseRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val caseClass = CaseClass.valueOf(
            requireNotNull(request.caseClass) { "caseClass is required" }.uppercase()
                .replace('-', '_'),
        )
        val subjectRef = requireNotNull(request.subjectRef) { "subjectRef is required" }
        val openedBy = requireNotNull(request.openedBy) { "openedBy is required" }
        val dispositionTarget = requireNotNull(request.dispositionTarget) { "dispositionTarget is required" }

        return when (val result = openService.open(openedBy, caseClass, subjectRef, dispositionTarget)) {
            is CaseOpenResult.Opened -> Response.status(Response.Status.CREATED)
                .entity(OpenCaseResponse(result.caseId)).build()
            CaseOpenResult.Denied -> Response.status(Response.Status.FORBIDDEN)
                .entity(errorBody("case.open denied for '$openedBy' or class '$caseClass' not enabled")).build()
            CaseOpenResult.Duplicate -> Response.status(Response.Status.CONFLICT)
                .entity(errorBody("a case for this class and subject already runs")).build()
            CaseOpenResult.RateLimited -> Response.status(HTTP_TOO_MANY_REQUESTS)
                .entity(errorBody("case-open quota or concurrent-case ceiling exhausted")).build()
            CaseOpenResult.Unavailable -> temporalUnavailable()
        }
    }

    @POST
    @Path("/cases/{caseId}/signals")
    @RolesAllowed("ROLE_ADMIN", "ROLE_OPERATOR")
    fun signal(@PathParam("caseId") caseId: String?, request: SignalRequest?): Response {
        val id = requireNotNull(caseId) { "caseId path parameter is required" }
        requireNotNull(request) { "request body is required" }
        val type = requireNotNull(request.type) { "type is required" }
        val agentId = requireNotNull(request.agentId) { "agentId is required" }
        if (!temporalConfig.enabled()) return temporalUnavailable()
        if (!capable(type, agentId)) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity(errorBody("signal '$type' denied for agent '$agentId'")).build()
        }
        return deliver(id, type, agentId, request)
    }

    private fun capable(type: String, agentId: String): Boolean = when (type) {
        "join" -> gate.canJoinCase(agentId)
        "contribute" -> gate.canContribute(agentId)
        "supersede" -> gate.canPreempt(agentId)
        "request-synthesis" -> gate.canRequestSynthesis(agentId)
        else -> throw IllegalArgumentException("unknown signal type '$type'")
    }

    private fun deliver(id: String, type: String, agentId: String, request: SignalRequest): Response {
        val stub = workflowClient.newWorkflowStub(CaseWorkflow::class.java, id)
        return try {
            when (type) {
                "join" -> stub.join(JoinSignal(agentId, request.role ?: "participant"))
                "contribute" -> stub.contribute(
                    ContributeSignal(
                        agentId = agentId,
                        summary = requireNotNull(request.summary) { "summary is required for contribute" },
                        evidenceRefs = request.evidenceRefs ?: emptyList(),
                        contested = request.contested ?: false,
                    ),
                )
                "supersede" -> stub.supersede(
                    SupersedeSignal(
                        agentId = agentId,
                        newEvidenceRef = requireNotNull(request.newEvidenceRef) {
                            "newEvidenceRef is required for supersede"
                        },
                        reason = request.reason ?: "superseded-by-evidence",
                    ),
                )
                else -> stub.requestSynthesis(SynthesisRequest(agentId))
            }
            Response.accepted().build()
        } catch (e: WorkflowNotFoundException) {
            log.debugf(e, "signal '%s' for unknown case %s", type, id)
            Response.status(Response.Status.NOT_FOUND)
                .entity(errorBody("no running case with id '$id'")).build()
        }
    }

    private fun temporalUnavailable(): Response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .entity(errorBody("Temporal case workflows are disabled (openbank.temporal.enabled=false)"))
        .build()

    private fun errorBody(message: String): Map<String, String> = mapOf("error" to message)

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_LIST_LIMIT = 200
        val log: org.jboss.logging.Logger = org.jboss.logging.Logger.getLogger(CaseCoordinatorResource::class.java)
    }
}
