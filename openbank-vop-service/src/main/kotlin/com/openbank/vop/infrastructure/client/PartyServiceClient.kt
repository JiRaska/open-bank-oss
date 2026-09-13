// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.client

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
 * Hop 2 of the VoP name resolution (ADR-0171 §4): partyId → the authoritative holder name.
 * `parties.legal_name` / `parties.trading_name` are the golden source for a party's name; no other
 * service may hold a second copy.
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
    fun getParty(@PathParam("id") id: String): Uni<PartySummary>
}

/**
 * Subset of party-service's party payload VoP acts on — a local mirror, never a shared type. Only
 * the two name fields are pulled: VoP compares names and must not fetch identifiers, birth data,
 * or contact details it has no use for (GDPR Art. 5(1)(c); party-service's
 * `V7__party_name_search_trgm.sql` sets the same precedent for its own search path).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartySummary(val legalName: String? = null, val tradingName: String? = null)
