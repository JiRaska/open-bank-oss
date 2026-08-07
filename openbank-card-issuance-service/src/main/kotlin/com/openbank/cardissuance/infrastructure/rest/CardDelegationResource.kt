// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardDelegationGuard
import com.openbank.cardissuance.domain.model.CardDelegationIntent
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * Machine-readable answer to "may this party do this on this card?" (ADR-0232 D3) —
 * the customer-edge slice asks here until it holds its own projection. Reuses the
 * existing `card.read` OPA action deliberately: it is the same question class, so no
 * rego change (mirrors consent-service's /active reusing consent.validate).
 */
@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CardDelegationResource(private val guard: CardDelegationGuard) {

    @GET
    @Path("/{id}/delegation/check")
    @RolesAllowed("ROLE_API", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.read", resource = "#id")
    @Operation(summary = "Whether a party may VIEW or MANAGE_LIMITS on a card (holder OR active delegation grant)")
    suspend fun check(
        @PathParam("id") id: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("intent") intent: CardDelegationIntent?,
    ): Response {
        // #3624 — both identify WHAT is being asked, so neither has a defensible default: an absent
        // `intent` cannot be assumed to be VIEW, and an absent `partyId` has no caller to check.
        // Declared non-nullable they answered 500; `suspend` emits no intrinsic, so the nulls
        // reached CardDelegationGuard. libs-runtime maps IllegalArgumentException to 400.
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        requireNotNull(intent) { "query parameter 'intent' is required" }
        val authorized = guard.isAuthorized(id, partyId, intent)
        return Response.ok(mapOf("authorized" to authorized)).build()
    }
}
