// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.onboarding.infrastructure.client

import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

@RegisterRestClient(configKey = "party-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PartyServiceClient {
    @PUT
    @Path("/{partyId}/kyc-status")
    fun suspendParty(@PathParam("partyId") partyId: UUID, body: Map<String, String>): Uni<Response>
}
