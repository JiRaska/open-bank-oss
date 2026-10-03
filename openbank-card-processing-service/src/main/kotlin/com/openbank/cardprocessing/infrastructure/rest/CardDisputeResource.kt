// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest

import com.openbank.cardprocessing.application.port.`in`.CardDisputeUseCase
import com.openbank.cardprocessing.application.port.`in`.OpenDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.SubmitEvidenceCommand
import com.openbank.cardprocessing.domain.model.DisputeOutcome
import com.openbank.cardprocessing.domain.model.DisputeRefusal
import com.openbank.cardprocessing.infrastructure.rest.dto.DisputeListResponse
import com.openbank.cardprocessing.infrastructure.rest.dto.DisputeResponseDto
import com.openbank.cardprocessing.infrastructure.rest.dto.OpenDisputeRequestDto
import com.openbank.cardprocessing.infrastructure.rest.dto.RefusalResponse
import com.openbank.cardprocessing.infrastructure.rest.dto.SubmitEvidenceRequestDto
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * The disputes desk: open a chargeback against cleared spend, file evidence, read the case.
 *
 * Opening fails closed when the network cannot be reached — there is no local-only case. See
 * [CardDisputeService][com.openbank.cardprocessing.application.usecase.CardDisputeService] for why:
 * a case the network never opened renders as active while its representment window expires.
 */
@Path("/api/v1/card-disputes")
@Produces(MediaType.APPLICATION_JSON)
class CardDisputeResource(private val useCase: CardDisputeUseCase) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.dispute", resource = "")
    @Operation(summary = "Open a chargeback case with the network against a cleared authorisation")
    suspend fun open(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: OpenDisputeRequestDto,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "header 'Idempotency-Key' must not be blank" }
        val outcome = useCase.open(
            OpenDisputeCommand(
                authorizationId = request.authorizationId,
                reasonCode = request.reasonCode,
                amountMinorUnits = request.amountMinorUnits,
                currencyCode = request.currencyCode,
                idempotencyKey = idempotencyKey,
            ),
        )
        return when (outcome) {
            is DisputeOutcome.Accepted ->
                Response.status(Response.Status.CREATED).entity(DisputeResponseDto.of(outcome.case)).build()
            is DisputeOutcome.Refused -> refusal(outcome)
        }
    }

    @POST
    @Path("/{id}/evidence")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.dispute", resource = "#id")
    @Operation(summary = "File evidence against an open case")
    suspend fun submitEvidence(@PathParam("id") id: UUID, request: SubmitEvidenceRequestDto): Response = respond(
        useCase.submitEvidence(SubmitEvidenceCommand(id, request.documentReference, request.note)),
    )

    @POST
    @Path("/{id}/refresh")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.dispute", resource = "#id")
    @Operation(summary = "Re-read the network's status for a case and record any move")
    suspend fun refresh(@PathParam("id") id: UUID): Response = respond(useCase.refreshStatus(id))

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.read", resource = "#id")
    @Operation(summary = "One dispute case, in both vocabularies")
    suspend fun byId(@PathParam("id") id: UUID): Response {
        val case = useCase.findById(id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(DisputeResponseDto.of(case)).build()
    }

    @GET
    @Path("/card/{cardId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.read", resource = "#cardId")
    @Operation(summary = "Dispute cases for one card, newest first")
    suspend fun byCard(@PathParam("cardId") cardId: UUID, @QueryParam("limit") limit: Int?): Response {
        val page = useCase.findByCard(cardId, (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
        return Response.ok(DisputeListResponse(page.map(DisputeResponseDto::of), page.size)).build()
    }

    private fun respond(outcome: DisputeOutcome): Response = when (outcome) {
        is DisputeOutcome.Accepted -> Response.ok(DisputeResponseDto.of(outcome.case)).build()
        is DisputeOutcome.Refused -> refusal(outcome)
    }

    private fun refusal(outcome: DisputeOutcome.Refused): Response {
        val status = when (outcome.reason) {
            DisputeRefusal.AUTHORIZATION_NOT_FOUND, DisputeRefusal.CASE_NOT_FOUND -> Response.Status.NOT_FOUND
            else -> Response.Status.CONFLICT
        }
        return Response.status(status)
            .entity(RefusalResponse(outcome.reason.name, outcome.detail ?: message(outcome.reason)))
            .build()
    }

    private fun message(reason: DisputeRefusal): String = when (reason) {
        DisputeRefusal.AUTHORIZATION_NOT_FOUND -> "no such authorisation"
        DisputeRefusal.NO_NETWORK_REFERENCE ->
            "the authorisation carries no acquirer reference, so the network cannot identify the transaction"
        DisputeRefusal.NOTHING_CLEARED -> "nothing cleared on this authorisation — release a hold with a reversal"
        DisputeRefusal.AMOUNT_EXCEEDS_CLEARED -> "the disputed amount exceeds what cleared"
        DisputeRefusal.ALREADY_DISPUTED -> "a live case already exists against this authorisation"
        DisputeRefusal.CASE_NOT_FOUND -> "no such dispute case"
        DisputeRefusal.CASE_TERMINAL -> "the case is closed and accepts no further evidence"
        DisputeRefusal.SCHEME_UNAVAILABLE -> "the dispute binding could not answer"
        DisputeRefusal.SCHEME_REFUSED -> "the network refused the request"
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
