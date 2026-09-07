// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.rest

import com.openbank.kyc.application.InvalidApprovalReasonException
import com.openbank.kyc.application.InvalidStateTransitionException
import com.openbank.kyc.application.KycCaseConflictException
import com.openbank.kyc.application.KycCaseNotFoundException
import com.openbank.kyc.application.KycService
import com.openbank.kyc.application.PepScreeningService
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.time.Instant
import java.util.UUID

@Path("/api/v1/kyc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "KYC")
class KycResource {

    @Inject lateinit var kycService: KycService

    @Inject lateinit var pepScreeningService: PepScreeningService

    @GET
    @Path("/cases")
    @RolesAllowed(
        Roles.VIEWER,
        Roles.OPERATOR,
        Roles.ADMIN,
        Roles.KYC,
        Roles.KYC_OPENER,
        Roles.KYC_REVIEWER,
        Roles.COMPLIANCE,
        Roles.API,
    )
    @Operation(
        summary = "List KYC cases. Optional ?status= filter for the onboarding cockpit funnel (ADR-0068).",
    )
    suspend fun listCases(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @QueryParam("status") statusParam: String?,
    ): Response {
        val status = statusParam?.uppercase()?.let { runCatching { KycCaseStatus.valueOf(it) }.getOrNull() }
        // Echo the EFFECTIVE window, never the raw query values. `size` was already clamped for the
        // query and echoed un-clamped, so `?size=500` answered `"size": 500` over at most 100 rows —
        // a caller computing ceil(total / size) off that pages past the end and renders a short list
        // as complete; the spec declares the parameter as 1..100, so the clamped value is the only
        // one it can honestly publish. `?page=-1` answered 200 while echoing `"page": -1`, handing a
        // pager a negative offset to page forward from. Pinned by KycCasePageApiContractTest.
        val effectivePage = page.coerceAtLeast(0)
        val effectiveSize = size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val items = kycService.listCases(effectivePage, effectiveSize, status)
        val total = kycService.countCases(status)
        return Response.ok(
            mapOf(
                "items" to items,
                "total" to total,
                "page" to effectivePage,
                "size" to effectiveSize,
                "statusFilter" to status?.name,
            ),
        ).build()
    }

    @POST
    @Path("/cases")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC_OPENER)
    @Operation(summary = "Open a new KYC case for a party")
    suspend fun openCase(req: OpenCaseRequest): Response {
        val case = kycService.openCase(req.partyId)
        return Response.created(URI.create("/api/v1/kyc/cases/${case.id}")).entity(case).build()
    }

    @GET
    @Path("/cases/{id}")
    @RolesAllowed(
        Roles.VIEWER,
        Roles.OPERATOR,
        Roles.ADMIN,
        Roles.KYC,
        Roles.KYC_OPENER,
        Roles.KYC_REVIEWER,
        Roles.COMPLIANCE,
        Roles.API,
    )
    @Operation(summary = "Get KYC case by ID")
    suspend fun getCase(@PathParam("id") id: UUID): Response = Response.ok(kycService.getCase(id)).build()

    @GET
    @Path("/cases/party/{partyId}")
    @RolesAllowed(
        Roles.VIEWER,
        Roles.OPERATOR,
        Roles.ADMIN,
        Roles.KYC,
        Roles.KYC_OPENER,
        Roles.KYC_REVIEWER,
        Roles.COMPLIANCE,
        Roles.API,
    )
    @Operation(summary = "Get latest KYC case for a party")
    suspend fun getCaseByParty(@PathParam("partyId") partyId: UUID): Response {
        val case = kycService.getCaseByParty(partyId)
            ?: return Response.status(404).build()
        return Response.ok(case).build()
    }

    @PUT
    @Path("/cases/{id}/checks/{checkType}")
    @RolesAllowed(Roles.ADMIN, Roles.KYC_OPENER)
    @Authorize(action = "kycCase.updateCheck", resource = "#id")
    @Operation(summary = "Update result of a specific KYC check")
    suspend fun updateCheck(
        @PathParam("id") id: UUID,
        @PathParam("checkType") checkType: String,
        req: UpdateCheckRequest,
    ): Response {
        val case = kycService.updateCheckStatus(
            id,
            CheckType.valueOf(checkType),
            CheckStatus.valueOf(req.status),
            req.result,
        )
        return Response.ok(case).build()
    }

    /**
     * Re-run the first-increment PEP screen (ADR-0116 delivery note) for an existing case.
     *
     * This is an **operator-triggered** re-screen, not the periodic re-KYC programme described in
     * ADR-0116 §5 (cadence LOW=5y/MEDIUM=3y/HIGH+=1y) — that still requires the Temporal scheduled
     * workflow flagged there as a separate follow-up. Useful when the PEP dataset has since been
     * refreshed (openbank-sanctions-service re-imports `PEP_GLOBAL` from OpenSanctions on its own
     * schedule) and an operator wants to confirm a case is still clear before approving it.
     */
    @POST
    @Path("/cases/{caseId}/pep-rescreen")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC_OPENER, Roles.COMPLIANCE)
    @Authorize(action = "kycCase.pepRescreen", resource = "#caseId")
    @Operation(
        summary = "Re-screen a KYC case's party name against the PEP_GLOBAL list (first increment, ADR-0116).",
        description = "Screens openbank-sanctions-service's OpenSanctions-derived PEP_GLOBAL list only — " +
            "not a paid commercial vendor feed, not identity-document verification, not continuous " +
            "real-time monitoring. A match escalates riskLevel and routes PEP_SCREENING to MANUAL_REVIEW.",
    )
    suspend fun rescreenPep(@PathParam("caseId") caseId: UUID, req: PepRescreenRequest): Response =
        Response.ok(pepScreeningService.screenCase(caseId, req.partyName)).build()

    /**
     * Approve a KYC case that is in UNDER_REVIEW status.
     *
     * Four-eyes mandate (ADR-0068, ČNB AML/KYC): the approver identity is derived from the
     * authenticated security context — the caller must not supply their own identity.
     * A mandatory [KycCaseApprovalRequest.reason] of at least 10 characters is required
     * for the audit trail (ČNB §8 KYC obligations, AML Act No. 253/2008 Coll.).
     */
    @POST
    @Path("/cases/{caseId}/approve")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC_REVIEWER)
    @Authorize(action = "kyc.case.approve", resource = "#caseId")
    @Operation(
        summary = "Approve KYC case — triggers party activation (four-eyes enforced, ADR-0068).",
        description = "Only cases in UNDER_REVIEW status may be approved. The approver identity is " +
            "taken from the authenticated session (ČNB four-eyes mandate). A reason of at least " +
            "10 characters is mandatory for the regulatory audit trail.",
    )
    suspend fun approveCase(
        @PathParam("caseId") caseId: UUID,
        req: KycCaseApprovalRequest,
        @Context secCtx: SecurityContext,
    ): Response {
        val approver = secCtx.userPrincipal?.name
            ?: return Response.status(Response.Status.UNAUTHORIZED).build()
        return Response.ok(kycService.approveCase(caseId, approver, req.reason)).build()
    }

    /**
     * Reject a KYC case that is in UNDER_REVIEW status.
     *
     * Four-eyes mandate (ADR-0068, ČNB AML/KYC): the rejector identity is derived from the
     * authenticated security context. A mandatory [KycCaseApprovalRequest.reason] of at least
     * 10 characters is required for the audit trail.
     */
    @POST
    @Path("/cases/{caseId}/reject")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC_REVIEWER)
    @Authorize(action = "kyc.case.reject", resource = "#caseId")
    @Operation(
        summary = "Reject KYC case (four-eyes enforced, ADR-0068).",
        description = "Only cases in UNDER_REVIEW status may be rejected. The rejector identity is " +
            "taken from the authenticated session. A reason of at least 10 characters is mandatory " +
            "for the regulatory audit trail (ČNB AML/KYC §8).",
    )
    suspend fun rejectCase(
        @PathParam("caseId") caseId: UUID,
        req: KycCaseApprovalRequest,
        @Context secCtx: SecurityContext,
    ): Response {
        val rejector = secCtx.userPrincipal?.name
            ?: return Response.status(Response.Status.UNAUTHORIZED).build()
        return Response.ok(kycService.rejectCase(caseId, rejector, req.reason)).build()
    }

    private companion object {
        /** Page-size bounds published by `openapi.yaml` for the `size` query parameter. */
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 100
    }
}

data class OpenCaseRequest(val partyId: UUID)
data class UpdateCheckRequest(val status: String, val result: String?)

/** Party legal name to screen — kept out of [com.openbank.kyc.domain.model.KycCase] (ADR-0116). */
data class PepRescreenRequest(val partyName: String)

/**
 * Request body for approve/reject operations.
 *
 * [reason] is mandatory (minimum 10 characters) to satisfy the ČNB four-eyes audit trail
 * requirement (AML Act No. 253/2008 Coll., §8 KYC documentation obligations). The minimum
 * length is enforced in [KycService.approveCase] and [KycService.rejectCase].
 */
data class KycCaseApprovalRequest(val reason: String)

@Provider
class KycNotFoundMapper : ExceptionMapper<KycCaseNotFoundException> {
    override fun toResponse(e: KycCaseNotFoundException) = Response.status(404)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                404,
                ErrorCode.NOT_FOUND.code,
                e.message ?: "Not found",
                timestamp = Instant.now(),
            ),
        ).build()
}

@Provider
class KycConflictMapper : ExceptionMapper<KycCaseConflictException> {
    override fun toResponse(e: KycCaseConflictException) = Response.status(ErrorCode.CONFLICT.httpStatus)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                ErrorCode.CONFLICT.httpStatus,
                ErrorCode.CONFLICT.code,
                e.message ?: "Conflict",
                timestamp = Instant.now(),
            ),
        ).build()
}

@Provider
class KycInvalidStateTransitionMapper : ExceptionMapper<InvalidStateTransitionException> {
    override fun toResponse(e: InvalidStateTransitionException) = Response.status(422)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                422,
                "INVALID_STATE_TRANSITION",
                e.message ?: "Invalid state transition",
                timestamp = Instant.now(),
            ),
        ).build()
}

@Provider
class KycInvalidApprovalReasonMapper : ExceptionMapper<InvalidApprovalReasonException> {
    override fun toResponse(e: InvalidApprovalReasonException) = Response.status(422)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                422,
                "INVALID_APPROVAL_REASON",
                e.message ?: "Invalid approval reason",
                timestamp = Instant.now(),
            ),
        ).build()
}
