// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.onboarding.TransferOutCommand
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.domain.transfer.Counterparty
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.authz.ContractAccessGuard.Companion.PARTY_HEADER
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The participant's transfers (ADR-0334 slice S2): request a transfer-out of their own contract
 * (SCA-signed) and follow any transfer of theirs. Same caller and ownership model as
 * `OnboardingResource`: edge only, party from `X-Customer-Party-Id`, another party's transfer or
 * contract answers 404.
 */
@Tag(name = "Pension transfers", description = "Transfers of a pension contract between providers")
@Path("/api/v2/pension/transfers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API)
class PensionTransferResource {

    @Inject
    lateinit var guard: ContractAccessGuard

    /** The acting participant, vouched for by the edge relay (ContractAccessGuard). */
    private fun partyOf(header: String?): UUID = checkNotNull(guard.actingParticipant(header).customerPartyId)

    @Inject
    lateinit var transfers: TransferService

    @POST
    @Path("/out")
    @Operation(summary = "Request a transfer-out of the caller's contract to another provider (SCA-signed)")
    @Authorize(action = "pension.transfer.request-out")
    suspend fun requestOut(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: TransferOutRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val transfer = transfers.requestTransferOut(
            TransferOutCommand(
                contractId = requireNotNull(body.contractId) { "contractId is required" },
                partyId = partyOf(party),
                receiving = body.toCounterparty(),
                challengeId = requireNotNull(
                    body.scaChallengeId?.takeIf {
                        it.isNotBlank()
                    },
                ) { "scaChallengeId is required" },
            ),
        )
        return Response.status(Response.Status.ACCEPTED).entity(TransferResponse.from(transfer)).build()
    }

    @POST
    @Path("/{id}/consent")
    @Operation(summary = "SCA-consent to a transfer-out a receiving provider requested for the caller's contract")
    @Authorize(action = "pension.transfer.consent", resource = "#id")
    suspend fun consent(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        @PathParam("id") id: UUID,
        request: TransferConsentRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val challenge =
            requireNotNull(request?.scaChallengeId?.takeIf { it.isNotBlank() }) { "scaChallengeId is required" }
        val transfer = transfers.consentTransferOut(id, partyOf(party), challenge)
        return Response.status(Response.Status.ACCEPTED).entity(TransferResponse.from(transfer)).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Status of one of the caller's transfers")
    @Authorize(action = "pension.transfer.inspect", resource = "#id")
    suspend fun get(@HeaderParam(PARTY_HEADER) party: String?, @PathParam("id") id: UUID): TransferResponse =
        TransferResponse.from(transfers.get(id, partyOf(party)))
}

internal fun TransferOutRequest.toCounterparty() = Counterparty(
    providerId = requireNotNull(receivingProviderId) { "receivingProviderId is required" },
    providerName = requireNotNull(receivingProviderName) { "receivingProviderName is required" },
    contractNumber = requireNotNull(receivingContractNumber) { "receivingContractNumber is required" },
)
