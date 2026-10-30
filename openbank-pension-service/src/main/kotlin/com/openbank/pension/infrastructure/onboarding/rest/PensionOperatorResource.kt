// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.IncentiveHistoryEntry
import com.openbank.pension.domain.transfer.TransferStatus
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Operator views and the provider-to-provider callbacks an operator (or the integration acting as
 * one) relays: the ceding provider's answer, the arrival of transferred funds, a receiving
 * provider's transfer-in answer and the first contribution of a new contract. A receiving
 * provider's transfer-out request is NOT relayed here: the provider authenticates itself on
 * `ReceivingProviderResource`.
 *
 * Staff only (`ROLE_OPERATOR` / `ROLE_ADMIN`); the rego extension additionally keeps every
 * `service-account-` principal off the write actions.
 */
@Tag(name = "Pension operations", description = "Operator views of onboarding and transfers")
@Path("/api/v2/pension/operator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
class PensionOperatorResource {

    @Inject
    lateinit var onboarding: OnboardingService

    @Inject
    lateinit var transfers: TransferService

    @GET
    @Path("/onboarding/applications")
    @Operation(summary = "Onboarding applications, newest first, optionally by status")
    @Authorize(action = "pension.operator.inspect")
    suspend fun applications(
        @QueryParam("status") status: OnboardingStatus?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<OperatorApplicationResponse> =
        onboarding.list(status, limit.coerceIn(1, MAX_LIMIT)).map(OperatorApplicationResponse::from)

    @GET
    @Path("/onboarding/applications/{id}")
    @Operation(summary = "One onboarding application")
    @Authorize(action = "pension.operator.inspect", resource = "#id")
    suspend fun application(@PathParam("id") id: UUID): OperatorApplicationResponse =
        OperatorApplicationResponse.from(onboarding.get(id, null))

    @POST
    @Path("/onboarding/applications/{id}/contribution-received")
    @Operation(summary = "Record the first contribution of a signed new contract (activates it per pack)")
    @Authorize(action = "pension.operator.contribution", resource = "#id")
    suspend fun contributionReceived(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
    ): OperatorApplicationResponse {
        requireIdempotencyKey(idempotencyKey)
        return OperatorApplicationResponse.from(onboarding.contributionReceived(id))
    }

    @GET
    @Path("/transfers")
    @Operation(summary = "Transfers in both directions, newest first, optionally by status")
    @Authorize(action = "pension.operator.inspect")
    suspend fun transferList(
        @QueryParam("status") status: TransferStatus?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<TransferResponse> = transfers.list(status, limit.coerceIn(1, MAX_LIMIT)).map(TransferResponse::from)

    @GET
    @Path("/transfers/{id}")
    @Operation(summary = "One transfer")
    @Authorize(action = "pension.operator.inspect", resource = "#id")
    suspend fun transfer(@PathParam("id") id: UUID): TransferResponse = TransferResponse.from(transfers.get(id, null))

    @POST
    @Path("/transfers/{id}/counterparty-response")
    @Operation(summary = "Relay the ceding provider's acceptance or rejection of a transfer-in")
    @Authorize(action = "pension.operator.transfer", resource = "#id")
    suspend fun counterpartyResponse(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
        request: CounterpartyResponseRequest?,
    ): TransferResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val accepted = requireNotNull(body.accepted) { "accepted is required" }
        val transfer = if (accepted) {
            transfers.counterpartyAccepted(id)
        } else {
            transfers.counterpartyRejected(
                id,
                requireNotNull(
                    body.reason?.takeIf {
                        it.isNotBlank()
                    },
                ) { "reason is required" },
            )
        }
        return TransferResponse.from(transfer)
    }

    @POST
    @Path("/transfers/{id}/funds-received")
    @Operation(summary = "Record transferred funds, incentive history and original start date of a transfer-in")
    @Authorize(action = "pension.operator.transfer", resource = "#id")
    suspend fun fundsReceived(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
        request: FundsReceivedRequest?,
    ): TransferResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val arrival = FundsArrival(
            amount = requireNotNull(body.amount) { "amount is required" },
            currency = requireNotNull(body.currency) { "currency is required" },
            originalStartDate = requireNotNull(body.originalStartDate) { "originalStartDate is required" },
            incentiveHistory = body.incentiveHistory.orEmpty().mapIndexed { i, entry ->
                val e = requireNotNull(entry) { "incentiveHistory[$i] must not be null" }
                IncentiveHistoryEntry(
                    incentiveId = requireNotNull(e.incentiveId) { "incentiveHistory[$i].incentiveId is required" },
                    year = requireNotNull(e.year) { "incentiveHistory[$i].year is required" },
                    amount = requireNotNull(e.amount) { "incentiveHistory[$i].amount is required" },
                )
            },
        )
        return TransferResponse.from(transfers.fundsReceived(id, arrival))
    }

    private companion object {
        const val MAX_LIMIT = 500
    }
}
