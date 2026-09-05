// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest

import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import com.openbank.cardprocessing.application.port.`in`.PresentmentCommand
import com.openbank.cardprocessing.application.port.out.PresentedMandate
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.PresentmentRefusal
import com.openbank.cardprocessing.infrastructure.rest.dto.AuthorizationListResponse
import com.openbank.cardprocessing.infrastructure.rest.dto.AuthorizationRequestDto
import com.openbank.cardprocessing.infrastructure.rest.dto.AuthorizationResponseDto
import com.openbank.cardprocessing.infrastructure.rest.dto.PresentmentRequestDto
import com.openbank.cardprocessing.infrastructure.rest.dto.RefusalResponse
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
 * The acquirer-facing surface: authorise, clear, reverse, read.
 *
 * ## Nullable parameters, deliberately
 *
 * `Idempotency-Key` is declared `String?` and checked with `requireNotNull` in the body. A
 * non-nullable `@HeaderParam` is a **500** for the absent header — JAX-RS injects null, and for a
 * `suspend fun` no intrinsic null check is emitted at all, so the null flows into the body and
 * fails at the first dereference. Three services shipped `require(key.isNotBlank())` that answered
 * 500 for exactly the case it was written for (#3104/#3624). libs-runtime maps
 * `IllegalArgumentException` to 400; never add a service-local mapper (#526).
 */
@Path("/api/v1/card-authorizations")
@Produces(MediaType.APPLICATION_JSON)
class CardProcessingResource(private val useCase: CardProcessingUseCase) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.authorize", resource = "")
    @Operation(summary = "Authorise a card transaction — decides, records and holds")
    suspend fun authorize(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: AuthorizationRequestDto,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "header 'Idempotency-Key' must not be blank" }
        val authorization = useCase.authorize(
            AuthorizationCommand(
                cardId = request.cardId,
                amountMinorUnits = request.amountMinorUnits,
                currencyCode = request.currencyCode,
                channel = request.channel,
                mcc = request.mcc,
                merchantName = request.merchantName,
                merchantCountry = request.merchantCountry,
                networkReference = request.networkReference,
                idempotencyKey = idempotencyKey,
                mandate = request.agentMandate?.let {
                    PresentedMandate(
                        kind = it.kind,
                        issuer = it.issuer,
                        subject = it.subject,
                        signingInput = it.signingInput,
                        signatureB64 = it.signatureB64,
                        algorithm = it.algorithm,
                        payee = it.payee,
                        amountCapMinorUnits = it.amountCapMinorUnits,
                        currency = it.currency,
                        expiresAt = it.expiresAt,
                        singleUse = it.singleUse,
                        agentId = it.agentId,
                    )
                },
            ),
        )
        // 201 for both outcomes: a decline is a created record of a decision, not a failed request.
        // Answering 4xx for a decline would make "the card was refused" indistinguishable from "the
        // request was malformed" to every acquirer and every dashboard.
        return Response.status(Response.Status.CREATED).entity(AuthorizationResponseDto.of(authorization)).build()
    }

    @POST
    @Path("/{id}/clearing")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.clear", resource = "#id")
    @Operation(summary = "Apply a clearing presentment and post the cleared amount to the books")
    suspend fun clear(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: PresentmentRequestDto,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "header 'Idempotency-Key' must not be blank" }
        return respond(
            useCase.clear(
                PresentmentCommand(
                    authorizationId = id,
                    amountMinorUnits = request.amountMinorUnits,
                    currencyCode = request.currencyCode,
                    idempotencyKey = idempotencyKey,
                ),
            ),
        )
    }

    @POST
    @Path("/{id}/reversal")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.reverse", resource = "#id")
    @Operation(summary = "Release the remaining hold at the acquirer's request")
    suspend fun reverse(@PathParam("id") id: UUID): Response = respond(useCase.reverse(id))

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.read", resource = "#id")
    @Operation(summary = "One authorisation, with its hold and cleared amounts")
    suspend fun byId(@PathParam("id") id: UUID): Response {
        val found = useCase.findById(id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(AuthorizationResponseDto.of(found)).build()
    }

    @GET
    @Path("/card/{cardId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "cardprocessing.read", resource = "#cardId")
    @Operation(summary = "Authorisations for one card, newest first")
    suspend fun byCard(@PathParam("cardId") cardId: UUID, @QueryParam("limit") limit: Int?): Response {
        val page = useCase.findByCard(cardId, (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
        return Response.ok(AuthorizationListResponse(page.map(AuthorizationResponseDto::of), page.size)).build()
    }

    /**
     * A refusal is **409 Conflict**, not 400: the request was well formed and the state was not what
     * it needed to be. The reason travels as a machine-readable value so an acquirer's retry logic
     * can tell "already cleared" from "you sent more than was authorised".
     */
    private fun respond(outcome: PresentmentOutcome): Response = when (outcome) {
        is PresentmentOutcome.Accepted ->
            Response.ok(AuthorizationResponseDto.of(outcome.authorization)).build()

        is PresentmentOutcome.Refused -> Response.status(Response.Status.CONFLICT)
            .entity(RefusalResponse(outcome.reason.name, refusalMessage(outcome.reason)))
            .build()
    }

    private fun refusalMessage(reason: PresentmentRefusal): String = when (reason) {
        PresentmentRefusal.NOT_HOLDING_FUNDS ->
            "the authorisation is not holding funds — it is already cleared, reversed, expired or declined"
        PresentmentRefusal.AMOUNT_NOT_POSITIVE -> "the presented amount must be positive"
        PresentmentRefusal.EXCEEDS_AUTHORIZED_AMOUNT ->
            "cumulative clearing would exceed the authorised amount"
        PresentmentRefusal.CURRENCY_MISMATCH ->
            "the presentment currency differs from the authorisation currency"
        PresentmentRefusal.NOT_YET_EXPIRED -> "the hold has not reached its expiry instant"
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
