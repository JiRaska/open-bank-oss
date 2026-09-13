// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.application.CaseCapabilityGate
import com.openbank.casecoordinator.application.CaseOpenResult
import com.openbank.casecoordinator.application.CaseOpenService
import com.openbank.casecoordinator.application.CaseSignalAuthorizationResult
import com.openbank.casecoordinator.application.CaseSignalAuthorizationService
import com.openbank.casecoordinator.application.CaseThreadService
import com.openbank.casecoordinator.application.workflow.CaseWorkflow
import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseSummary
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import com.openbank.libs.temporal.TemporalConfig
import io.quarkus.security.identity.SecurityIdentity
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
    private val identity: SecurityIdentity,
    private val signalAuthorization: CaseSignalAuthorizationService,
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

        // The authenticated caller, not the body's claim. `openedBy` still names WHICH agent
        // identity the call acts as (ADR-0244 D9 charter capability), but the open-rate quota is
        // keyed on this instead — a ceiling keyed on a value the caller picks is not a ceiling
        // (#4834). Same separation McpEndpoint documents for X-Agent-Id: the bearer proves who,
        // the header only names which.
        val callerPrincipal = identity.principal?.name?.takeIf { it.isNotBlank() } ?: "anonymous"

        // …and the identity it may CLAIM is decided against the roles it proved, before any
        // capability decision runs on the claim (#4834). `canOpenCase(openedBy)` asks whether the
        // named agent holds `case.open`; it cannot ask whether the caller is that agent, so on its
        // own it tests an assertion. Deny-by-default, and the value is not echoed back.
        if (!gate.permitsAssertedIdentity(identity.roles, openedBy)) return assertedIdentityDenied()

        return when (
            val result = openService.open(callerPrincipal, openedBy, caseClass, subjectRef, dispositionTarget)
        ) {
            is CaseOpenResult.Opened -> Response.status(Response.Status.CREATED)
                .entity(OpenCaseResponse(result.caseId)).build()
            CaseOpenResult.Denied -> Response.status(Response.Status.FORBIDDEN)
                // The caller's own openedBy is NOT echoed back (#4215). It is free-form request
                // input; reflecting it verbatim into a response body serves no diagnostic purpose
                // the caller does not already have, and it is the same untrusted value the log
                // line downstream had to sanitise. The class is a server-side enum, so it stays.
                .entity(errorBody("case.open denied for the requested agent, or class not enabled"))
                .build()
            CaseOpenResult.Duplicate -> Response.status(Response.Status.CONFLICT)
                .entity(errorBody("a case for this class and subject already runs")).build()
            CaseOpenResult.RateLimited -> Response.status(HTTP_TOO_MANY_REQUESTS)
                .entity(errorBody("case-open quota or concurrent-case ceiling exhausted")).build()
            CaseOpenResult.Unavailable -> temporalUnavailable()
        }
    }

    @POST
    @Path("/cases/{caseId}/signals")
    @Blocking
    @RolesAllowed("ROLE_ADMIN", "ROLE_OPERATOR")
    fun signal(@PathParam("caseId") caseId: String?, request: SignalRequest?): Response {
        val id = requireNotNull(caseId) { "caseId path parameter is required" }
        requireNotNull(request) { "request body is required" }
        val type = requireNotNull(request.type) { "type is required" }
        val agentId = requireNotNull(request.agentId) { "agentId is required" }
        // Authorisation before availability, deliberately ahead of the Temporal check (#4834). The
        // claimed agentId is carried into the workflow as the AUTHOR of the contribution, which is
        // the guarantee ADR-0244 rests on — "who detected is never who coordinated" — so it must be
        // one the caller proved it may act as, not one it named. Answering 503 first would also
        // make the decision unobservable wherever Temporal is off.
        if (!gate.permitsAssertedIdentity(identity.roles, agentId)) return assertedIdentityDenied()
        val capability = when (type) {
            "join" -> "case.join"
            "contribute" -> "case.contribute"
            "supersede", "request-synthesis" -> null
            else -> throw IllegalArgumentException("unknown signal type '$type'")
        }
        val collaborationAuthorization = capability?.let {
            when (val result = signalAuthorization.authorize(id, agentId, it)) {
                is CaseSignalAuthorizationResult.Authorized -> result
                CaseSignalAuthorizationResult.Denied -> return Response.status(Response.Status.FORBIDDEN)
                    .entity(errorBody("signal '$type' denied for the requested agent")).build()
                CaseSignalAuthorizationResult.UnknownCase -> return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorBody("no running case with id '$id'")).build()
                CaseSignalAuthorizationResult.PolicyUnavailable -> return Response.status(
                    Response.Status.SERVICE_UNAVAILABLE,
                )
                    .entity(errorBody("case collaboration policy is unavailable")).build()
            }
        }
        if (!temporalConfig.enabled()) return temporalUnavailable()
        if (capability == null && !capable(type, agentId)) {
            return Response.status(Response.Status.FORBIDDEN)
                // agentId is NOT echoed (#4834), matching the openCase denial. It is free-form
                // request-body input, and reflecting it verbatim tells the caller nothing it did
                // not just send. `type` stays: reaching this line means capable() already matched
                // it against the four known signal literals, so it is a bounded server-side value.
                .entity(errorBody("signal '$type' denied for the requested agent")).build()
        }
        return deliver(id, type, agentId, request, capability, collaborationAuthorization)
    }

    private fun capable(type: String, agentId: String): Boolean = when (type) {
        "join" -> gate.canJoinCase(agentId)
        "contribute" -> gate.canContribute(agentId)
        "supersede" -> gate.canPreempt(agentId)
        "request-synthesis" -> gate.canRequestSynthesis(agentId)
        else -> throw IllegalArgumentException("unknown signal type '$type'")
    }

    private fun deliver(
        id: String,
        type: String,
        agentId: String,
        request: SignalRequest,
        capability: String?,
        authorization: CaseSignalAuthorizationResult.Authorized?,
    ): Response {
        val stub = workflowClient.newWorkflowStub(CaseWorkflow::class.java, id)
        return try {
            when (type) {
                "join" -> stub.join(
                    JoinSignal(
                        agentId,
                        request.role ?: "participant",
                        requireNotNull(authorization).signalId,
                        authorization.rolloutId,
                    ),
                )
                "contribute" -> stub.contribute(
                    ContributeSignal(
                        agentId = agentId,
                        summary = requireNotNull(request.summary) { "summary is required for contribute" },
                        evidenceRefs = request.evidenceRefs ?: emptyList(),
                        contested = request.contested ?: false,
                        signalId = requireNotNull(authorization).signalId,
                        rolloutId = authorization.rolloutId,
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
            if (capability != null && authorization != null) {
                signalAuthorization.recordInvoked(id, agentId, capability, authorization)
            }
            Response.accepted().build()
        } catch (e: WorkflowNotFoundException) {
            log.debugf(e, "signal '%s' for unknown case %s", type, id)
            Response.status(Response.Status.NOT_FOUND)
                .entity(errorBody("no running case with id '$id'")).build()
        }
    }

    /**
     * The asserted agent identity is not one this caller's roles may act as. The requested id is
     * NOT echoed, for the same reason the other denials stopped echoing it (#4215): it is
     * free-form request input, and repeating it back tells the caller nothing it did not send.
     */
    private fun assertedIdentityDenied(): Response = Response.status(Response.Status.FORBIDDEN)
        .entity(errorBody("the authenticated caller may not act as the requested agent identity"))
        .build()

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
