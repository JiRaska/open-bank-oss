// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.onboarding.TransferOutCommand
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * A receiving provider asking for a participant's contract to be transferred to it.
 *
 * Authenticated AS that provider: the caller's principal must be exactly
 * `service-account-pension-provider-<receivingProviderId>`, so one provider can neither speak for
 * another nor be impersonated by the customer edge (and the rego extension grants this action to
 * that principal prefix only). The request carries no consent of its own: it is created
 * AWAITING_CONSENT and moves only when the participant SCA-consents on `/transfers/{id}/consent`.
 */
@Tag(name = "Pension provider transfers", description = "Transfer-out requests from a receiving provider")
@Path("/api/v2/pension/provider/transfers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API)
class ReceivingProviderResource {

    @Inject
    lateinit var transfers: TransferService

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/out")
    @Operation(summary = "Request a transfer-out to the calling provider; waits for the participant's SCA consent")
    @Authorize(action = "pension.transfer.provider-request")
    suspend fun requestOut(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: TransferOutRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val receiving = body.toCounterparty()
        if (identity.principal?.name != PROVIDER_PRINCIPAL_PREFIX + receiving.providerId) {
            throw ForbiddenException("the caller is not provider ${receiving.providerId}")
        }
        val transfer = transfers.requestTransferOut(
            TransferOutCommand(
                contractId = requireNotNull(body.contractId) { "contractId is required" },
                partyId = null,
                receiving = receiving,
                challengeId = null,
            ),
        )
        return Response.status(Response.Status.ACCEPTED).entity(TransferResponse.from(transfer)).build()
    }

    companion object {
        const val PROVIDER_PRINCIPAL_PREFIX = "service-account-pension-provider-"
    }
}
