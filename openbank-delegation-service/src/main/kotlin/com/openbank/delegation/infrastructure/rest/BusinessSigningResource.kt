// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.usecase.BusinessSigningService
import com.openbank.delegation.application.usecase.PaymentApprovalCommand
import com.openbank.delegation.application.usecase.SigningPayloadCodec
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.delegation.domain.model.SignerGroup
import com.openbank.delegation.domain.model.SigningAmount
import com.openbank.delegation.infrastructure.rest.dto.ApprovalRequestResponse
import com.openbank.delegation.infrastructure.rest.dto.CreatePaymentApprovalRequest
import com.openbank.delegation.infrastructure.rest.dto.EvaluateRequest
import com.openbank.delegation.infrastructure.rest.dto.EvaluationResponse
import com.openbank.delegation.infrastructure.rest.dto.ListEnvelope
import com.openbank.delegation.infrastructure.rest.dto.MoneyBody
import com.openbank.delegation.infrastructure.rest.dto.PendingForHumanResponse
import com.openbank.delegation.infrastructure.rest.dto.ProposeGroupRequest
import com.openbank.delegation.infrastructure.rest.dto.ProposePayeeRequest
import com.openbank.delegation.infrastructure.rest.dto.ProposePolicyRequest
import com.openbank.delegation.infrastructure.rest.dto.RejectionBody
import com.openbank.delegation.infrastructure.rest.dto.ReleaseClaimResponse
import com.openbank.delegation.infrastructure.rest.dto.ReleaseResultBody
import com.openbank.delegation.infrastructure.rest.dto.RemovePayeeBody
import com.openbank.delegation.infrastructure.rest.dto.SignatureBody
import com.openbank.delegation.infrastructure.rest.dto.SignerGroupBody
import com.openbank.delegation.infrastructure.rest.dto.SigningPolicyResponse
import com.openbank.delegation.infrastructure.rest.dto.TrustedPayeeResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Duration
import java.util.UUID

/**
 * ADR-0312 internal API. The only intended caller is customer-edge's service account (OPA:
 * `edge-service-business-signing`, identified by `principal.id`); the edge authenticates the human
 * and states the signer's party id, and every signature is additionally bound to that human's own
 * SCA challenge — the entity is never a signer.
 */
@Tag(name = "Business signing")
@Path("/api/v1/entities/{entityId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
@Suppress("TooManyFunctions")
class BusinessSigningResource(private val service: BusinessSigningService, private val codec: SigningPayloadCodec) {

    @POST
    @Path("/signing/evaluate")
    @Authorize(action = "delegation.signing.evaluate", resource = "#entityId")
    @Operation(summary = "How many signatures a payment needs, and from whom")
    suspend fun evaluate(@PathParam("entityId") entityId: UUID, body: EvaluateRequest?): EvaluationResponse {
        requireNotNull(body) { "request body is required" }
        val amount =
            SigningAmount(
                requireNotNull(body.amount) {
                    "amount is required"
                },
                requireNotNull(body.currency?.uppercase()) { "currency is required" },
            )
        return EvaluationResponse.from(
            service.evaluate(
                entityId,
                amount,
                requireNotNull(body.creditorIban) { "creditorIban is required" },
                requireNotNull(body.rail) { "rail is required" },
            ),
        )
    }

    @GET
    @Path("/signing-policy")
    @Authorize(action = "delegation.signing.policy.read", resource = "#entityId")
    @Operation(summary = "The effective signing policy (stored, or derived from the register)")
    suspend fun policy(@PathParam("entityId") entityId: UUID): SigningPolicyResponse =
        SigningPolicyResponse.from(service.effectivePolicy(entityId))

    @PUT
    @Path("/signing-policy")
    @Authorize(action = "delegation.signing.policy.propose", resource = "#entityId")
    @Operation(summary = "Propose a policy change (creates a POLICY_CHANGE approval request)")
    suspend fun proposePolicy(@PathParam("entityId") entityId: UUID, body: ProposePolicyRequest?): Response {
        requireNotNull(body) { "request body is required" }
        val rules = requireNotNull(body.rules) { "rules is required" }.map { it.toDomain() }
        val request = service.proposePolicy(
            entityId,
            initiator(body.initiatorPartyId),
            rules,
            body.trustedPayeeCap?.toDomain("trustedPayeeCap"),
        )
        return accepted(request)
    }

    @GET
    @Path("/signer-groups")
    @Authorize(action = "delegation.signing.policy.read", resource = "#entityId")
    @Operation(summary = "Named signer groups")
    suspend fun groups(@PathParam("entityId") entityId: UUID): ListEnvelope<SignerGroupBody> =
        ListEnvelope(service.groups(entityId).map(SignerGroupBody::from))

    @POST
    @Path("/signer-groups")
    @Authorize(action = "delegation.signing.policy.propose", resource = "#entityId")
    @Operation(summary = "Propose a signer-group create/edit (a POLICY_CHANGE approval request)")
    suspend fun proposeGroup(@PathParam("entityId") entityId: UUID, body: ProposeGroupRequest?): Response {
        requireNotNull(body) { "request body is required" }
        val group = SignerGroup(
            id = requireNotNull(body.id) { "id is required" },
            entityPartyId = entityId,
            name = requireNotNull(body.name) { "name is required" }.trim(),
            memberPartyIds = requireNotNull(body.memberPartyIds) { "memberPartyIds is required" }.toSet(),
        )
        return accepted(service.proposeGroup(entityId, initiator(body.initiatorPartyId), group))
    }

    @GET
    @Path("/trusted-payees")
    @Authorize(action = "delegation.signing.payees.read", resource = "#entityId")
    @Operation(summary = "ACTIVE trusted payees")
    suspend fun payees(@PathParam("entityId") entityId: UUID): ListEnvelope<TrustedPayeeResponse> =
        ListEnvelope(service.trustedPayees(entityId).map(TrustedPayeeResponse::from))

    @POST
    @Path("/trusted-payees")
    @Authorize(action = "delegation.signing.payees.propose", resource = "#entityId")
    @Operation(summary = "Propose a trusted payee (a PAYEE_ADD approval request; trusted only once approved)")
    suspend fun proposePayee(@PathParam("entityId") entityId: UUID, body: ProposePayeeRequest?): Response {
        requireNotNull(body) { "request body is required" }
        return accepted(
            service.proposePayeeAdd(
                entityId,
                initiator(body.initiatorPartyId),
                requireNotNull(body.iban) { "iban is required" },
                requireNotNull(body.name) { "name is required" },
                body.bic,
            ),
        )
    }

    @DELETE
    @Path("/trusted-payees/{payeeId}")
    @Authorize(action = "delegation.signing.payees.propose", resource = "#entityId")
    @Operation(summary = "Propose removing a trusted payee (a PAYEE_REMOVE approval request)")
    suspend fun proposePayeeRemoval(
        @PathParam("entityId") entityId: UUID,
        @PathParam("payeeId") payeeId: UUID,
        @QueryParam("initiatorPartyId") initiatorPartyId: UUID?,
        body: RemovePayeeBody?,
    ): Response =
        accepted(service.proposePayeeRemove(entityId, initiator(initiatorPartyId ?: body?.initiatorPartyId), payeeId))

    @POST
    @Path("/approval-requests")
    @Authorize(action = "delegation.signing.approval.create", resource = "#entityId")
    @Operation(
        summary = "Hold a payment for co-signature (kind PAYMENT; the initiator's consumed SCA is the first signature)",
    )
    suspend fun createPayment(@PathParam("entityId") entityId: UUID, body: CreatePaymentApprovalRequest?): Response {
        requireNotNull(body) { "request body is required" }
        require(body.kind == null || body.kind == "PAYMENT") { "only kind PAYMENT is created here" }
        val initiator = requireNotNull(body.initiatorSignature) { "initiatorSignature is required" }
        val payload = requireNotNull(body.payload) { "payload is required" }
        require(payload["railRequest"] is Map<*, *>) { "payload.railRequest must be an object" }
        val request = service.createPayment(
            PaymentApprovalCommand(
                entityPartyId = entityId,
                initiatorPartyId = requireNotNull(initiator.partyId) { "initiatorSignature.partyId is required" },
                initiatorScaChallengeId = requireNotNull(initiator.scaChallengeId) {
                    "initiatorSignature.scaChallengeId is required"
                },
                amount = MoneyBody(
                    payload["amount"]?.toString()?.toBigDecimalOrNull(),
                    payload["currency"] as? String,
                ).toDomain("payload"),
                creditorIban = requireNotNull(payload["creditorIban"] as? String) {
                    "payload.creditorIban is required"
                },
                creditorName = payload["creditorName"] as? String,
                rail = requireNotNull(
                    (payload["rail"] as? String)?.takeIf {
                        it.isNotBlank()
                    },
                ) { "payload.rail is required" },
                payload = payload,
                ttl = body.expiresInSeconds?.let { Duration.ofSeconds(it) },
            ),
        )
        return Response.status(Response.Status.CREATED).entity(ApprovalRequestResponse.from(request, codec)).build()
    }

    @GET
    @Path("/approval-requests")
    @Authorize(action = "delegation.signing.approval.read", resource = "#entityId")
    @Operation(summary = "Approval requests of the entity, optionally by status and signer")
    suspend fun list(
        @PathParam("entityId") entityId: UUID,
        @QueryParam("status") status: String?,
        @QueryParam("signer") signer: UUID?,
        @QueryParam("initiator") initiator: UUID?,
    ): ListEnvelope<ApprovalRequestResponse> {
        val parsed = status?.let { s ->
            ApprovalStatus.entries.firstOrNull { it.name == s.uppercase() }
                ?: throw IllegalArgumentException("unknown status '$s'")
        }
        return ListEnvelope(
            service.list(entityId, parsed, signer, initiator).map {
                ApprovalRequestResponse.from(it, codec)
            },
        )
    }

    @GET
    @Path("/approval-requests/{approvalId}")
    @Authorize(action = "delegation.signing.approval.read", resource = "#entityId")
    @Operation(summary = "One approval request with its frozen payload and signatures")
    suspend fun get(
        @PathParam("entityId") entityId: UUID,
        @PathParam("approvalId") approvalId: UUID,
    ): ApprovalRequestResponse = ApprovalRequestResponse.from(service.get(entityId, approvalId), codec)

    @POST
    @Path("/approval-requests/{approvalId}/signatures")
    @Authorize(action = "delegation.signing.approval.sign", resource = "#entityId")
    @Operation(summary = "Add a signature; the SCA challenge must be linked to approvalId + payloadSha256")
    suspend fun sign(
        @PathParam("entityId") entityId: UUID,
        @PathParam("approvalId") approvalId: UUID,
        body: SignatureBody?,
    ): ApprovalRequestResponse {
        requireNotNull(body) { "request body is required" }
        val signed = service.sign(
            entityId,
            approvalId,
            requireNotNull(body.partyId) { "partyId is required" },
            requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
        )
        return ApprovalRequestResponse.from(signed, codec)
    }

    @POST
    @Path("/approval-requests/{approvalId}/rejection")
    @Authorize(action = "delegation.signing.approval.reject", resource = "#entityId")
    @Operation(summary = "Reject a pending request (any eligible signer)")
    suspend fun reject(
        @PathParam("entityId") entityId: UUID,
        @PathParam("approvalId") approvalId: UUID,
        body: RejectionBody?,
    ): ApprovalRequestResponse {
        requireNotNull(body) { "request body is required" }
        return ApprovalRequestResponse.from(
            service.reject(entityId, approvalId, requireNotNull(body.partyId) { "partyId is required" }, body.reason),
            codec,
        )
    }

    @POST
    @Path("/approval-requests/{approvalId}/release-claim")
    @Authorize(action = "delegation.signing.approval.claim", resource = "#entityId")
    @Operation(summary = "Single-use claim of an APPROVED payment: the token and frozen payload, once; 409 after")
    suspend fun claim(
        @PathParam("entityId") entityId: UUID,
        @PathParam("approvalId") approvalId: UUID,
    ): ReleaseClaimResponse {
        val claim = service.claimRelease(entityId, approvalId)
        return ReleaseClaimResponse(claim.claimToken, codec.parseObject(claim.payload))
    }

    @POST
    @Path("/approval-requests/{approvalId}/release-result")
    @Authorize(action = "delegation.signing.approval.report", resource = "#entityId")
    @Operation(summary = "Record what the rail answered for a claimed payment")
    suspend fun releaseResult(
        @PathParam("entityId") entityId: UUID,
        @PathParam("approvalId") approvalId: UUID,
        body: ReleaseResultBody?,
    ): ApprovalRequestResponse {
        requireNotNull(body) { "request body is required" }
        val ok = requireNotNull(body.ok) { "ok is required" }
        return ApprovalRequestResponse.from(
            service.recordReleaseResult(entityId, approvalId, ok, body.releaseRef, body.error, body.claimToken),
            codec,
        )
    }

    private fun initiator(id: UUID?): UUID = requireNotNull(id) { "initiatorPartyId is required" }

    private fun accepted(request: com.openbank.delegation.domain.model.ApprovalRequest): Response =
        Response.status(Response.Status.ACCEPTED).entity(ApprovalRequestResponse.from(request, codec)).build()
}

/** Everything waiting for one human's signature across all entities they may sign for now. */
@Tag(name = "Business signing")
@Path("/api/v1/parties/{humanId}/approval-requests")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class PendingApprovalsResource(private val service: BusinessSigningService, private val codec: SigningPayloadCodec) {
    @GET
    @Path("/pending")
    @Authorize(action = "delegation.signing.pending.read", resource = "#humanId")
    @Operation(summary = "Approval requests waiting for this human's signature, grouped by entity")
    suspend fun pending(@PathParam("humanId") humanId: UUID): PendingForHumanResponse =
        PendingForHumanResponse.from(humanId, service.pendingFor(humanId), codec)
}

/** One mapper for the whole signing boundary: `{status, error, code}`. */
@jakarta.ws.rs.ext.Provider
class BusinessSigningExceptionMapper :
    jakarta.ws.rs.ext.ExceptionMapper<com.openbank.delegation.application.usecase.BusinessSigningException> {
    override fun toResponse(exception: com.openbank.delegation.application.usecase.BusinessSigningException): Response =
        Response.status(exception.status)
            .entity(mapOf("status" to exception.status, "error" to exception.message, "code" to exception.code))
            .type(MediaType.APPLICATION_JSON)
            .build()
}
