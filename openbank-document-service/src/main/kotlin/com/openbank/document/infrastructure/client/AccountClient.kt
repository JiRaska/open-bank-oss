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
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * RestClient binding to `openbank-account-service`'s party-scoped account list — narrow, mirroring
 * [ProductCatalogClient]. Feeds the RAMCOVA_SMLOUVA template's `{{account.iban}}`/`{{product.*}}`
 * clause (Article 2, "the Customer is provided with a payment account …"): by the time the
 * framework agreement is signed the account already exists (ADR-0162 D7 — account.created fires
 * before the sign step), so this is real on-file data, not a placeholder.
 */
@RegisterRestClient(configKey = "account-service-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface AccountClient {
    @GET
    @Path("/api/v1/accounts")
    fun listByParty(@QueryParam("partyId") partyId: String): Uni<AccountPageClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountPageClientResponse(val data: List<AccountClientResponse> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountClientResponse(
    val id: String,
    val accountNumber: String,
    val accountType: String,
    val productId: String,
    val status: String,
)
