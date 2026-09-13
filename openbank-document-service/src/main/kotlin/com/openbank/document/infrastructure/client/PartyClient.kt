// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-party-service`'s party read endpoint — narrow, mirroring
 * [ProductCatalogClient]. The only fields this service needs are the ones the RAMCOVA_SMLOUVA
 * template's `{{party.*}}` placeholders reference (name, address): rendering a legal contract
 * against `data = emptyMap()` left "(the "Customer")" and a blank address on a real signed
 * document — this is what fills them in with the party's actual (or KYC-synthetic, per
 * ADR-0069) on-file details.
 */
@RegisterRestClient(configKey = "party-service-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface PartyClient {
    @GET
    @Path("/api/v1/parties/{id}")
    fun getById(@PathParam("id") id: String): Uni<PartyClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyClientResponse(val id: String, val legalName: String, val address: PartyAddressClientResponse? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyAddressClientResponse(
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
)
