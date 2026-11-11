// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-party-service` (`GET /api/v1/parties/{id}`), the golden source of
 * a party's name. Carries the service OIDC token via the reactive client filter, like the other
 * inter-service clients.
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PartyServiceClient {

    @GET
    @Path("/{id}")
    fun getParty(@PathParam("id") id: String): Uni<PartyNameView>
}

/**
 * The name fields and the party kind of party-service's party payload — a local mirror, never a
 * shared type. `partyType` carries party-service's `PartyType` name (INDIVIDUAL, SOLE_TRADER,
 * COMPANY, TRUST) and is kept a string so an added kind cannot fail deserialisation.
 * `ignoreUnknown` is load-bearing: the payload also carries identifiers, birth data and contact
 * details, and not binding them keeps that personal data out of this service's heap and logs
 * (GDPR Art. 5(1)(c)); a sanctions screen needs the names and the kind and nothing else.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyNameView(val legalName: String? = null, val tradingName: String? = null, val partyType: String? = null)
