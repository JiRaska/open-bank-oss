// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest

import com.openbank.cardprocessing.application.port.`in`.CardTokenUseCase
import com.openbank.cardprocessing.application.port.`in`.ChangeTokenStatusCommand
import com.openbank.cardprocessing.application.port.`in`.ProvisionTokenCommand
import com.openbank.cardprocessing.domain.model.TokenOutcome
import com.openbank.cardprocessing.domain.model.TokenRefusal
import com.openbank.cardprocessing.infrastructure.rest.dto.ChangeTokenStatusRequestDto
import com.openbank.cardprocessing.infrastructure.rest.dto.ProvisionTokenRequestDto
import com.openbank.cardprocessing.infrastructure.rest.dto.RefusalResponse
import com.openbank.cardprocessing.infrastructure.rest.dto.TokenListResponse
import com.openbank.cardprocessing.infrastructure.rest.dto.TokenResponseDto
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import jakarta.annotation.security.RolesAllowed
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
import java.util.UUID

/**
 * Network tokens for a card: provision, list, change state.
 *
 * `Idempotency-Key` is declared `String?` and checked in the body. A non-nullable `@HeaderParam` is
 * a **500** for the absent header, and for a `suspend fun` no null-check intrinsic is emitted at
 * all, so a guard written against a non-nullable parameter is dead code (#3104, #3624).
 * libs-runtime maps `IllegalArgumentException` to 400; never add a service-local mapper (#526).
 */
@Path("/api/v1/card-tokens")
@Produces(MediaType.APPLICATION_JSON)
class CardTokenResource(private val useCase: CardTokenUseCase) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.token", resource = "")
    @Operation(summary = "Provision a network token for a card at a wallet or merchant requestor")
    suspend fun provision(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: ProvisionTokenRequestDto,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "header 'Idempotency-Key' must not be blank" }
        val outcome = useCase.provision(
            ProvisionTokenCommand(
                cardId = request.cardId,
                requestorId = request.requestorId,
                requestorLabel = request.requestorLabel,
                idempotencyKey = idempotencyKey,
            ),
        )
        return when (outcome) {
            is TokenOutcome.Provisioned ->
                Response.status(Response.Status.CREATED).entity(TokenResponseDto.of(outcome.registration)).build()
            is TokenOutcome.Changed ->
                Response.ok(TokenResponseDto.of(outcome.registration)).build()
            is TokenOutcome.Refused -> refusal(outcome)
        }
    }

    @POST
    @Path("/{tokenReference}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.token", resource = "#tokenReference")
    @Operation(summary = "Suspend, resume or delete a network token")
    suspend fun changeStatus(
        @PathParam("tokenReference") tokenReference: String,
        request: ChangeTokenStatusRequestDto,
    ): Response {
        val status = runCatching { NetworkTokenStatus.valueOf(request.status.uppercase()) }.getOrNull()
        // A bad enum value is the CLIENT's mistake, so it is a 400 naming the accepted values —
        // not an IllegalArgumentException from valueOf carrying Java's own wording.
        requireNotNull(status) {
            "status must be one of ${NetworkTokenStatus.entries.joinToString(", ") { it.name }}"
        }
        return when (val outcome = useCase.changeStatus(ChangeTokenStatusCommand(tokenReference, status))) {
            is TokenOutcome.Changed -> Response.ok(TokenResponseDto.of(outcome.registration)).build()
            is TokenOutcome.Provisioned -> Response.ok(TokenResponseDto.of(outcome.registration)).build()
            is TokenOutcome.Refused -> refusal(outcome)
        }
    }

    @GET
    @Path("/card/{cardId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.read", resource = "#cardId")
    @Operation(summary = "Tokens for one card, with the provenance of the answer")
    suspend fun byCard(@PathParam("cardId") cardId: UUID): Response =
        Response.ok(TokenListResponse.of(useCase.listForCard(cardId))).build()

    /**
     * A refusal is **409 Conflict**, except the two that are genuinely "no such thing" (404).
     *
     * The request was well formed in every case, so 400 would be wrong; and a scheme that cannot be
     * reached is not a client error either. The reason travels as a machine-readable value so a
     * caller can tell "this token is deleted" from "the network is unreachable" without parsing
     * prose — the same rule the money path's presentment refusals follow.
     */
    private fun refusal(outcome: TokenOutcome.Refused): Response {
        val status = when (outcome.reason) {
            TokenRefusal.CARD_NOT_FOUND, TokenRefusal.TOKEN_NOT_FOUND -> Response.Status.NOT_FOUND
            else -> Response.Status.CONFLICT
        }
        return Response.status(status)
            .entity(RefusalResponse(outcome.reason.name, outcome.detail ?: message(outcome.reason)))
            .build()
    }

    private fun message(reason: TokenRefusal): String = when (reason) {
        TokenRefusal.CARD_NOT_FOUND -> "no such card, or card-issuance could not be reached to confirm it"
        TokenRefusal.TOKEN_NOT_FOUND -> "no such token"
        TokenRefusal.TOKEN_TERMINAL -> "the token is DELETED, which is terminal in every scheme"
        TokenRefusal.SCHEME_UNAVAILABLE -> "the tokenisation binding could not answer"
        TokenRefusal.SCHEME_REFUSED -> "the network refused the request"
    }
}
