// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Typed client for kyc-service's per-party case lookup, used by the GDPR Art. 15 export.
 *
 * `GET /api/v1/kyc/cases/party/{partyId}` is `@RolesAllowed(VIEWER, OPERATOR, ADMIN, KYC, …)`, so
 * the call must carry an M2M bearer via [OidcClientRequestReactiveFilter] (oidc-client
 * `openbank-services` → ROLE_OPERATOR). Before this client existed the adapter issued a raw
 * `java.net.http` GET with no Authorization header, which every deployed environment answered 401.
 */
@RegisterRestClient(configKey = "kyc-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/kyc/cases")
@Produces(MediaType.APPLICATION_JSON)
interface KycServiceRestClient {

    @GET
    @Path("/party/{partyId}")
    fun getCaseByParty(@PathParam("partyId") partyId: UUID): Uni<Map<String, Any?>>
}

/**
 * Typed client for card-issuance-service's per-party card list, used by the GDPR Art. 15 export.
 *
 * `GET /api/v1/cards/party/{partyId}` is `@RolesAllowed("ROLE_VIEWER","ROLE_OPERATOR","ROLE_ADMIN")`
 * plus `@Authorize(action = "card.list")`; the shared `rest.rego` operator-read-any rule admits the
 * ROLE_OPERATOR the client_credentials token carries, so the same M2M filter covers both hops.
 */
@RegisterRestClient(configKey = "card-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
interface CardServiceRestClient {

    @GET
    @Path("/party/{partyId}")
    fun listByParty(@PathParam("partyId") partyId: UUID): Uni<List<Map<String, Any?>>>
}

/**
 * Request/response shapes mirror consent-service's own `ConsentDtos.kt` structurally — this is a
 * separate Gradle module with no shared DTO dependency, so field names/types are kept in lockstep
 * by convention (Jackson serializes/deserializes by shape, not class identity). `granteeType` and
 * `scopes` are plain strings rather than consent-service's enums for the same reason; the values
 * sent (`INTERNAL_SERVICE`, `MARKETING_COMMS_EMAIL`/`_PUSH`/`_INAPP`) must match those enums' names.
 */
data class CreateMarketingConsentRequest(
    val partyId: UUID,
    val granteeId: String,
    val granteeType: String,
    val granteeName: String,
    val scopes: Set<String>,
    val accountIbans: List<String>?,
    val validTo: OffsetDateTime,
    val redirectUri: String?,
    val tppTransactionId: String?,
)

data class RevokeMarketingConsentRequest(val reason: String)

/** Only the fields the forwarder actually needs from consent-service's ConsentResponse. */
data class MarketingConsentResponse(val id: UUID, val status: String)

/**
 * Typed client for consent-service's consent lifecycle, used by the marketing-consent forwarder
 * (ADR-0206 D5). Both calls are `@Authorize`-gated and scoped to grantee
 * `party-service:marketing-comms` only (ADR-0206 D2) — this client's M2M identity is the same
 * shared `openbank-services` client every other REST client in this file uses, so the grantee
 * scoping in consent-service's OPA policy is what keeps this client from being able to
 * grant/revoke any OTHER party's or grantee's consent.
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ConsentServiceRestClient {

    @POST
    fun create(request: CreateMarketingConsentRequest): Uni<MarketingConsentResponse>

    @DELETE
    @Path("/{id}")
    fun revoke(
        @PathParam("id") id: UUID,
        @QueryParam("partyId") partyId: UUID,
        @QueryParam("granteeId") granteeId: String,
        request: RevokeMarketingConsentRequest,
    ): Uni<MarketingConsentResponse>
}
