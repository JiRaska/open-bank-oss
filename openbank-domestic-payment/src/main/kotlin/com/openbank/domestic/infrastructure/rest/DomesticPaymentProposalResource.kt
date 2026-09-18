// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.usecase.CreatePaymentProposalDraftOutcome
import com.openbank.domestic.application.usecase.DomesticPaymentProposalDraftService
import com.openbank.domestic.domain.model.DomesticPaymentProposalDraft
import com.openbank.domestic.infrastructure.rest.dto.CreateDomesticPaymentRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import java.time.Instant
import java.util.UUID

/** Workload-only draft writer. No route in this resource can approve or execute a payment. */
@Path("/api/v1/domestic-payment-proposals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class DomesticPaymentProposalResource(private val drafts: DomesticPaymentProposalDraftService) {
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/drafts")
    @RolesAllowed("ROLE_OPERATOR")
    @Authorize(action = "domestic-payment.create")
    @Operation(summary = "Prepare an immutable domestic payment proposal draft")
    suspend fun createDraft(
        request: CreateDomesticPaymentRequest,
        @HeaderParam("X-Customer-Party-Id") makerHeader: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        // Quarkus validates the issuer/signature before this resource runs. The customer-party
        // header is meaningful only for the dedicated edge service account, never for a human
        // operator token carrying the same ROLE_OPERATOR role.
        if (!isCustomerEdge(identity.principal as? JsonWebToken)) return forbidden()
        val maker = makerHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return forbidden()
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        require(request.transferScope == null && request.technicalAccountCode == null) {
            "A proposal draft cannot select an execution route or technical account"
        }

        return when (
            val result = drafts.create(
                maker,
                request.toCommand(
                    idempotencyKey,
                    synthetic = requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true,
                ),
            )
        ) {
            is CreatePaymentProposalDraftOutcome.Saved -> {
                val response = Response.status(Response.Status.CREATED)
                    .entity(result.draft.toResponse())
                if (result.replayed) response.header("X-Idempotency-Replayed", "true")
                response.build()
            }
            CreatePaymentProposalDraftOutcome.Refused -> forbidden()
            CreatePaymentProposalDraftOutcome.IdempotencyConflict -> Response.status(Response.Status.CONFLICT)
                .entity(mapOf("error" to "IDEMPOTENCY_KEY_REUSED"))
                .build()
        }
    }

    private fun forbidden(): Response = Response.status(Response.Status.FORBIDDEN)
        .entity(mapOf("error" to "FORBIDDEN"))
        .build()

    internal fun isCustomerEdge(jwt: JsonWebToken?): Boolean = jwt?.getClaim<String>("azp") == EDGE_CLIENT_ID &&
        jwt.getClaim<String>("preferred_username") == EDGE_SERVICE_ACCOUNT

    private companion object {
        const val EDGE_CLIENT_ID = "openbank-edge"
        const val EDGE_SERVICE_ACCOUNT = "service-account-openbank-edge"
    }
}

data class DomesticPaymentProposalDraftResponse(
    val id: UUID,
    val status: String,
    val makerPartyId: UUID,
    val ownerPartyId: UUID,
    val debtorAccountId: UUID,
    val amount: java.math.BigDecimal,
    val currency: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

private fun DomesticPaymentProposalDraft.toResponse() = DomesticPaymentProposalDraftResponse(
    id = id,
    status = "DRAFT",
    makerPartyId = makerPartyId,
    ownerPartyId = ownerPartyId,
    debtorAccountId = instruction.debtorAccountId,
    amount = instruction.amount,
    currency = instruction.currency,
    creditorAccountNumber = instruction.creditorAccountNumber,
    creditorBankCode = instruction.creditorBankCode,
    creditorName = instruction.creditorName,
    createdAt = createdAt,
    expiresAt = expiresAt,
)
