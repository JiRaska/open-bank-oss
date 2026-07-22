// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
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
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
interface CardServiceRestClient {

    @GET
    @Path("/party/{partyId}")
    fun listByParty(@PathParam("partyId") partyId: UUID): Uni<List<Map<String, Any?>>>
}
