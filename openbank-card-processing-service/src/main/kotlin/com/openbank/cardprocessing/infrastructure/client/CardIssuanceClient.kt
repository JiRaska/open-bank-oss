// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * The two things card-processing asks card-issuance for: who owns the card, and may this
 * authorisation go through.
 *
 * The paths are LITERAL here and literal again in the consumer pact's expectation. Deriving the
 * expectation from this annotation is DRY and vacuous — both sides then move together and the test
 * stays green against a route that does not exist, which is how a call to a ledger path that has
 * never existed shipped (#2269).
 */
@Path("/api/v1/cards")
@RegisterRestClient(configKey = "card-issuance-api")
@OidcClientFilter
@Produces(MediaType.APPLICATION_JSON)
interface CardIssuanceClient {

    @GET
    @Path("/{id}")
    suspend fun getCard(@PathParam("id") id: UUID): CardSummaryResponse

    @POST
    @Path("/{id}/authorizations")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun authorize(
        @PathParam("id") id: UUID,
        request: IssuerAuthorizationRequest,
    ): IssuerAuthorizationResponse
}

/**
 * Only the fields card-processing needs. Jackson ignores the rest by fleet configuration, so a
 * field card-issuance adds cannot break this consumer — but a field it RENAMES will, which is what
 * the committed pact and its provider replay exist to catch before a deploy does.
 */
data class CardSummaryResponse(
    val id: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val currencyCode: String? = null,
    val status: String? = null,
)

data class IssuerAuthorizationRequest(
    val amountMinorUnits: Long,
    val channel: String,
    val mcc: String?,
    val countryCode: String?,
    val spentTodayMinorUnits: Long,
    val spentThisMonthMinorUnits: Long,
    val spentThisMonthInCategoryMinorUnits: Long,
)

/**
 * The field is `declineReason`, matching card-issuance's `AuthorizationDecisionResponse` exactly.
 *
 * Worth stating because getting it wrong is silent: Jackson leaves an unmatched property null, so a
 * mis-named field here would make every decline arrive with no reason at all and the customer would
 * be told nothing rather than told why. The committed pact and its provider replay hold this name
 * to the producer's.
 */
data class IssuerAuthorizationResponse(val approved: Boolean, val declineReason: String?, val category: String)
