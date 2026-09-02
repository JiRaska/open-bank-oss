// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.CheckDelegationUseCase
import com.openbank.delegation.application.port.`in`.GetDelegationUseCase
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationUseCase
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.application.port.`in`.PreviewDelegationUseCase
import com.openbank.delegation.application.port.`in`.RespondDelegationUseCase
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationUseCase
import com.openbank.delegation.application.port.`in`.SuspendDelegationCommand
import com.openbank.delegation.infrastructure.rest.dto.CheckDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.DelegationCheckResponse
import com.openbank.delegation.infrastructure.rest.dto.DelegationPreviewResponse
import com.openbank.delegation.infrastructure.rest.dto.DelegationResponse
import com.openbank.delegation.infrastructure.rest.dto.OfferDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.PreviewDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.RevokeDelegationRequest
import com.openbank.delegation.infrastructure.rest.dto.SuspendDelegationRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Delegations", description = "Customer-to-party delegated access lifecycle (ADR-0232)")
@Path("/api/v1/delegations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class DelegationResource(
    private val offerDelegation: OfferDelegationUseCase,
    private val previewDelegation: PreviewDelegationUseCase,
    private val respondDelegation: RespondDelegationUseCase,
    private val revokeDelegation: RevokeDelegationUseCase,
    private val getDelegation: GetDelegationUseCase,
    private val checkDelegation: CheckDelegationUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @Operation(summary = "Validate a delegation draft without consuming SCA or creating a grant")
    @POST
    @Path("/preview")
    @Authorize(action = "delegation.preview", resource = "#request.grantorPartyId")
    suspend fun preview(
        request: PreviewDelegationRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationPreviewResponse {
        requireNotNull(request) { "request body is required" }
        previewDelegation.preview(
            PreviewDelegationCommand(
                callerPartyId = customerPartyId,
                grantorPartyId = request.grantorPartyId,
                granteePartyId = request.granteePartyId,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                capabilities = request.capabilities,
                approvalPolicy = request.approvalPolicy,
                requiredApprovals = request.requiredApprovals,
                perTransactionLimit = request.perTransactionLimit?.toDomain(),
                dailyLimit = request.dailyLimit?.toDomain(),
                monthlyLimit = request.monthlyLimit?.toDomain(),
                exposure = request.exposure?.toDomain(),
                validTo = request.validTo,
            ),
        )
        return DelegationPreviewResponse()
    }

    // SecurityIdentity, not @Context SecurityContext: in a Kotlin `suspend` resource method the
    // @Context principal does not reliably resolve to the bearer token — the same reason
    // AccountResource and ApprovalResource inject it this way.
    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * Bank-side actor. NOTE the known limitation: every backend service authenticates on the
     * shared `openbank-services` Keycloak client whose service account carries ROLE_OPERATOR
     * (documented at length in consent_rest_ext.rego), so this predicate alone does not
     * distinguish staff from any other service. Narrowing that is delegation_rest_ext.rego's job
     * — it excludes `service-account-openbank-services` from the operator write rules. This
     * check is the defence-in-depth half, not the whole gate.
     */
    private fun isBankOperator(): Boolean = identity.roles.any { it == "ROLE_OPERATOR" || it == "ROLE_ADMIN" }

    @Operation(summary = "Offer a delegation grant (OFFERED); idempotent via X-Request-ID")
    @POST
    @Authorize(action = "delegation.offer", resource = "#request.grantorPartyId")
    suspend fun offer(
        request: OfferDelegationRequest?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @Context uriInfo: UriInfo,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val idempotencyKey = xRequestId?.takeIf { it.isNotBlank() }

        idempotencyKey?.let { key ->
            idempotencyStore.get(offerKey(request.grantorPartyId, key))?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val grant = offerDelegation.offer(
            OfferDelegationCommand(
                callerPartyId = customerPartyId,
                grantorPartyId = request.grantorPartyId,
                granteePartyId = request.granteePartyId,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                capabilities = request.capabilities,
                approvalPolicy = request.approvalPolicy,
                requiredApprovals = request.requiredApprovals,
                perTransactionLimit = request.perTransactionLimit?.toDomain(),
                dailyLimit = request.dailyLimit?.toDomain(),
                monthlyLimit = request.monthlyLimit?.toDomain(),
                exposure = request.exposure?.toDomain(),
                validTo = request.validTo,
                grantScaSessionId = request.grantScaSessionId,
                note = request.note,
            ),
        )
        val responseBody = DelegationResponse.from(grant)
        idempotencyKey?.let { key ->
            idempotencyStore.save(
                offerKey(request.grantorPartyId, key),
                201,
                objectMapper.writeValueAsString(responseBody),
            )
        }

        return Response.created(uriInfo.absolutePathBuilder.path(grant.id.toString()).build())
            .entity(responseBody).build()
    }

    @Operation(summary = "Get delegation grant by ID")
    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.read", resource = "#id")
    suspend fun getById(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationResponse = DelegationResponse.from(getDelegation.getDelegation(id, customerPartyId))

    @Operation(summary = "List grants offered BY a party (Shared by me)")
    @GET
    @Path("/grantor/{partyId}")
    @Authorize(action = "delegation.list", resource = "#partyId")
    suspend fun listByGrantor(
        @PathParam("partyId") partyId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): List<DelegationResponse> =
        getDelegation.listByGrantor(partyId, customerPartyId).map { DelegationResponse.from(it) }

    @Operation(summary = "List grants offered TO a party (Shared with me)")
    @GET
    @Path("/grantee/{partyId}")
    @Authorize(action = "delegation.list", resource = "#partyId")
    suspend fun listByGrantee(
        @PathParam("partyId") partyId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): List<DelegationResponse> =
        getDelegation.listByGrantee(partyId, customerPartyId).map { DelegationResponse.from(it) }

    @Operation(summary = "Accept an OFFERED grant after the grantee's SCA challenge completes")
    @POST
    @Path("/{id}/accept")
    @Authorize(action = "delegation.accept", resource = "#id")
    suspend fun accept(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID?,
        @QueryParam("scaSessionId") scaSessionId: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationResponse {
        // #3104 — both identify WHO is accepting and under which SCA session. Absent, they used to
        // reach the use case as null and answer 500.
        requireNotNull(granteePartyId) { "query parameter 'granteePartyId' is required" }
        requireNotNull(scaSessionId) { "query parameter 'scaSessionId' is required" }
        return DelegationResponse.from(
            respondDelegation.accept(id, granteePartyId, scaSessionId, customerPartyId),
        )
    }

    @Operation(summary = "Decline an OFFERED grant (grantee)")
    @POST
    @Path("/{id}/decline")
    @Authorize(action = "delegation.decline", resource = "#id")
    suspend fun decline(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationResponse {
        // #3104
        requireNotNull(granteePartyId) { "query parameter 'granteePartyId' is required" }
        return DelegationResponse.from(respondDelegation.decline(id, granteePartyId, customerPartyId))
    }

    @Operation(summary = "Renounce an ACTIVE grant (grantee gives the access back)")
    @POST
    @Path("/{id}/renounce")
    @Authorize(action = "delegation.renounce", resource = "#id")
    suspend fun renounce(
        @PathParam("id") id: UUID,
        @QueryParam("granteePartyId") granteePartyId: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationResponse {
        // #3104
        requireNotNull(granteePartyId) { "query parameter 'granteePartyId' is required" }
        return DelegationResponse.from(respondDelegation.renounce(id, granteePartyId, customerPartyId))
    }

    /**
     * `revokedBy` used to come from the query string and was both the authorisation ("you may
     * revoke this") and the audit record (`closedBy`). Any authenticated caller could therefore
     * revoke anyone's grant and sign it with someone else's party id. It is now DERIVED: a
     * customer-scoped call revokes as the party the edge authenticated, and the query parameter
     * survives only for the bank-initiated path (role-gated, and narrowed further by
     * delegation_rest_ext.rego).
     */
    @Operation(summary = "Revoke a grant (grantor or bank); transitions to REVOKED and enqueues DelegationRevoked")
    @DELETE
    @Path("/{id}")
    @Authorize(action = "delegation.revoke", resource = "#id")
    suspend fun revoke(
        @PathParam("id") id: UUID,
        @QueryParam("revokedBy") revokedBy: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        request: RevokeDelegationRequest?,
    ): DelegationResponse {
        requireNotNull(request) { "request body is required" }
        val bankInitiated = customerPartyId == null && isBankOperator()
        val actor = customerPartyId
            ?: revokedBy?.takeIf { bankInitiated }
            ?: throw ForbiddenException("revoke requires a customer-scoped caller or a bank operator")
        return DelegationResponse.from(
            revokeDelegation.revoke(RevokeDelegationCommand(id, actor, request.reason, bankInitiated)),
        )
    }

    /**
     * Bank-side only (ADR-0232 D4: fraud/AML signal). Role-narrowed here rather than left to the
     * class-level ROLE_API: suspending someone else's grant is not a customer act, and a
     * customer-scoped call is refused outright rather than silently treated as staff.
     */
    @Operation(summary = "Suspend an ACTIVE grant (bank / fraud-AML signal)")
    @POST
    @Path("/{id}/suspend")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "delegation.suspend", resource = "#id")
    suspend fun suspend(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        request: SuspendDelegationRequest?,
    ): DelegationResponse {
        requireNotNull(request) { "request body is required" }
        requireBankCaller(customerPartyId)
        return DelegationResponse.from(revokeDelegation.suspend(SuspendDelegationCommand(id, request.reason)))
    }

    @Operation(summary = "Reinstate a SUSPENDED grant")
    @POST
    @Path("/{id}/reinstate")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "delegation.reinstate", resource = "#id")
    suspend fun reinstate(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): DelegationResponse {
        requireBankCaller(customerPartyId)
        return DelegationResponse.from(revokeDelegation.reinstate(id))
    }

    @Operation(
        summary = "Whether an active grant covers a capability on a resource (services without a local projection)",
    )
    @POST
    @Path("/check")
    @Authorize(action = "delegation.check")
    suspend fun check(request: CheckDelegationRequest?): DelegationCheckResponse {
        requireNotNull(request) { "request body is required" }
        return DelegationCheckResponse.from(
            checkDelegation.check(
                CheckDelegationCommand(
                    granteePartyId = request.granteePartyId,
                    resourceType = request.resourceType,
                    resourceId = request.resourceId,
                    capability = request.capability,
                    amount = request.amount?.toDomain(),
                ),
            ),
        )
    }

    private fun requireBankCaller(customerPartyId: UUID?) {
        if (customerPartyId != null || !isBankOperator()) {
            throw ForbiddenException("this operation is bank-initiated only")
        }
    }

    private fun offerKey(grantorPartyId: UUID, requestId: String) = "delegation:offer:$grantorPartyId:$requestId"

    companion object {
        /**
         * Contract: matches customer-edge UpstreamClient.PARTY_HEADER and
         * AccountResource.CUSTOMER_PARTY_HEADER. The edge stamps every customer-scoped upstream
         * call with the caller's validated party id under this header.
         */
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
    }
}
