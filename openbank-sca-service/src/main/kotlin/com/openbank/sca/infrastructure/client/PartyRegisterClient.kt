// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.sca.application.port.out.PartyTypeLookup
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * `GET /api/v1/parties/{id}` on party-service — read ONLY for `partyType` (#10281 item 1).
 * The reactive OIDC filter attaches the service token; the endpoint admits `ROLE_API`.
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyRegisterClient {

    @GET
    @Path("/{id}")
    fun getParty(@PathParam("id") id: UUID): Uni<PartyTypeView>
}

/**
 * Only the type is bound. `ignoreUnknown` is load-bearing: the party detail carries the legal
 * name, e-mail and KYC facts, and not binding them keeps that PII out of this service's heap.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyTypeView(val partyType: String? = null)

@ApplicationScoped
class PartyRegisterTypeLookup(@RestClient private val client: PartyRegisterClient) : PartyTypeLookup {

    override suspend fun partyType(partyId: UUID): String? = try {
        client.getParty(partyId).awaitSuspending().partyType
    } catch (e: WebApplicationException) {
        // A 404 is an answer ("no such party" — refused as not-a-person); every other status is
        // the register failing to answer, which must surface as unavailable, not as a refusal.
        if (e.response?.status == NOT_FOUND) null else throw e
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}
