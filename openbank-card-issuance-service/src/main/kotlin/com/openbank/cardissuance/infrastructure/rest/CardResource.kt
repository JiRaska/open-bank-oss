// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.port.`in`.CardStatusCommand
import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.application.port.`in`.ReadSecureDetailsQuery
import com.openbank.cardissuance.application.port.`in`.UpdateControlsCommand
import com.openbank.cardissuance.application.port.`in`.UpdateLimitsCommand
import com.openbank.cardissuance.infrastructure.rest.dto.CardStatusRequest
import com.openbank.cardissuance.infrastructure.rest.dto.IssueCardRequest
import com.openbank.cardissuance.infrastructure.rest.dto.UpdateControlsRequest
import com.openbank.cardissuance.infrastructure.rest.dto.UpdateLimitsRequest
import com.openbank.cardissuance.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cards", description = "Card issuance and lifecycle management — PCI DSS compliant")
@Suppress("TooManyFunctions") // one REST handler per card operation (issue/lifecycle/limits/controls)
class CardResource(private val cardUseCase: CardUseCase) {

    // #10486 batch 6: the named-caller check on the two machine-read endpoints below needs the caller.
    @Inject
    lateinit var securityIdentity: SecurityIdentity

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.create", resource = "")
    @Operation(summary = "Issue a new card")
    suspend fun issueCard(req: IssueCardRequest, @HeaderParam("Idempotency-Key") key: String?): Response {
        // #3624 — this guard answered 500 in exactly the case it was written for. `suspend` emits no
        // Intrinsics.checkNotNullParameter, so an ABSENT header arrived as null and
        // `null.isNotBlank()` threw NPE; only a BLANK header ever reached the intended 400.
        require(!key.isNullOrBlank()) { "Idempotency-Key header required" }
        val card = cardUseCase.issueCard(req.toCommand(key))
        return Response.created(URI.create("/api/v1/cards/${card.id}")).entity(card.toResponse()).build()
    }

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "")
    @Operation(summary = "List all cards")
    suspend fun listAll(): Response = Response.ok(cardUseCase.listAll().map { it.toResponse() }).build()

    // #10486 batch 6: ROLE_API admits delegation-service's OWN machine principal (resource-ownership
    // check) once the shared openbank-services client loses ROLE_OPERATOR. ROLE_API is held by every
    // service account, so it is narrowed twice: by identity in card_issuance_rest_ext.rego
    // (`service-delegation-card-read`) and, while card-issuance runs AUTHZ_ENFORCE=false (advisory), by
    // [requireNamedCardReader] here.
    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "card.read", resource = "#id")
    @Operation(summary = "Get card by ID")
    suspend fun getCard(@PathParam("id") id: UUID): Response {
        requireNamedCardReader(securityIdentity, CARD_READ_CALLERS)
        return cardUseCase.getCard(id)?.let { Response.ok(it.toResponse()).build() }
            ?: Response.status(404).entity(mapOf("error" to "Card not found")).build()
    }

    @GET
    @Path("/account/{accountId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "#accountId")
    @Operation(summary = "List cards by account")
    suspend fun listByAccount(@PathParam("accountId") accountId: UUID): Response =
        Response.ok(cardUseCase.listByAccount(accountId).map { it.toResponse() }).build()

    // #10486 batch 6: ROLE_API admits party-service's OWN machine principal (GDPR Art. 15 aggregation),
    // narrowed by identity in card_issuance_rest_ext.rego (`service-party-card-list`) and, while
    // card-issuance runs OPA advisory, by [requireNamedCardReader] here.
    @GET
    @Path("/party/{partyId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "card.list", resource = "#partyId")
    @Operation(summary = "List cards by party")
    suspend fun listByParty(@PathParam("partyId") partyId: UUID, @QueryParam("limit") limit: Int?): Response {
        requireNamedCardReader(securityIdentity, CARD_PARTY_LIST_CALLERS)
        val cards = if (limit == null) {
            cardUseCase.listByParty(partyId)
        } else {
            require(limit in 1..MAX_PARTY_LIST_LIMIT) { "limit must be between 1 and $MAX_PARTY_LIST_LIMIT" }
            cardUseCase.listByParty(partyId, limit)
        }
        return Response.ok(cards.map { it.toResponse() }).build()
    }

    /**
     * A virtual card's synthetic PAN/CVV. `no-store` is mandatory: this body must not sit in a
     * proxy, a browser cache or a service-worker after the one render it was fetched for.
     */
    @GET
    @Path("/{id}/secure-details")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.details.read", resource = "#id")
    @Operation(summary = "Read a virtual card's synthetic PAN/CVV (VIRTUAL and SINGLE_USE only)")
    suspend fun secureDetails(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String?): Response {
        val details = cardUseCase.readSecureDetails(ReadSecureDetailsQuery(id, operatorId ?: "unknown"))
        return Response.ok(details.toResponse())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Pragma", "no-cache")
            .build()
    }

    @GET
    @Path("/party/{partyId}/entitlements")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.list", resource = "#partyId")
    @Operation(summary = "Card entitlements of a party on a product (product-catalog cardConfig)")
    suspend fun entitlements(
        @PathParam("partyId") partyId: UUID,
        @QueryParam("productCode") productCode: String?,
    ): Response {
        require(!productCode.isNullOrBlank()) { "productCode query parameter required" }
        return Response.ok(cardUseCase.getEntitlements(partyId, productCode).toResponse()).build()
    }

    @POST
    @Path("/{id}/activate")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.activate", resource = "#id")
    @Operation(summary = "Activate a pending card")
    suspend fun activate(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String?): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(cardUseCase.activateCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()
    }

    @POST
    @Path("/{id}/block")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "card.block", resource = "#id")
    @Operation(summary = "Block a card")
    suspend fun block(
        @PathParam("id") id: UUID,
        req: CardStatusRequest,
        @HeaderParam("X-Operator-Id") operatorId: String?,
    ): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(cardUseCase.blockCard(CardStatusCommand(id, req.reason, operatorId)).toResponse()).build()
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "card.cancel", resource = "#id")
    @Operation(summary = "Cancel a card permanently (terminal)")
    suspend fun cancel(
        @PathParam("id") id: UUID,
        req: CardStatusRequest,
        @HeaderParam("X-Operator-Id") operatorId: String?,
    ): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(cardUseCase.cancelCard(CardStatusCommand(id, req.reason, operatorId)).toResponse()).build()
    }

    @POST
    @Path("/{id}/suspend")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.suspend", resource = "#id")
    @Operation(summary = "Suspend a card temporarily")
    suspend fun suspend(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String?): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(cardUseCase.suspendCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()
    }

    @POST
    @Path("/{id}/resume")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.resume", resource = "#id")
    @Operation(summary = "Resume a suspended card")
    suspend fun resume(@PathParam("id") id: UUID, @HeaderParam("X-Operator-Id") operatorId: String?): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(cardUseCase.resumeCard(CardStatusCommand(id, null, operatorId)).toResponse()).build()
    }

    @PUT
    @Path("/{id}/limits")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.limits.update", resource = "#id")
    @Operation(summary = "Set a card's daily and monthly spending limits")
    suspend fun updateLimits(
        @PathParam("id") id: UUID,
        req: UpdateLimitsRequest,
        @HeaderParam("X-Operator-Id") operatorId: String?,
    ): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(
            cardUseCase.updateLimits(
                UpdateLimitsCommand(id, req.dailyLimitMinorUnits, req.monthlyLimitMinorUnits, operatorId),
            ).toResponse(),
        ).build()
    }

    @PUT
    @Path("/{id}/controls")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.controls.update", resource = "#id")
    @Operation(summary = "Set a card's channel controls (contactless / online / ATM / abroad)")
    suspend fun updateControls(
        @PathParam("id") id: UUID,
        req: UpdateControlsRequest,
        @HeaderParam("X-Operator-Id") operatorId: String?,
    ): Response {
        requireNotNull(operatorId) { OPERATOR_ID_REQUIRED }
        return Response.ok(
            cardUseCase.updateControls(
                UpdateControlsCommand(
                    id,
                    req.contactlessEnabled,
                    req.onlineEnabled,
                    req.atmEnabled,
                    req.abroadEnabled,
                    operatorId,
                ),
            ).toResponse(),
        ).build()
    }

    private companion object {
        const val MAX_PARTY_LIST_LIMIT = 200

        // #3624 — X-Operator-Id is the AUDIT attribution on every card lifecycle write, so a null
        // flowing through is worse than the 500 it actually produced. These are `suspend` handlers:
        // no Intrinsics.checkNotNullParameter is emitted, and the null was carried into
        // CardStatusCommand / UpdateLimitsCommand / UpdateControlsCommand. libs-runtime maps
        // IllegalArgumentException to 400 with the ApiError envelope and a traceId.
        //
        // Deliberately NOT the `?: "unknown"` fallback secureDetails uses: that read is a lookup,
        // these seven change a card's state and must not be attributed to a placeholder operator.
        const val OPERATOR_ID_REQUIRED = "header 'X-Operator-Id' is required"
    }
}
