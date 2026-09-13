// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
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
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Typed read-only client for account-service: resolves partyId for a given accountId.
 * Used by [com.openbank.balance.infrastructure.rest.BalanceResource] to enforce per-account
 * ownership when the caller supplies X-Customer-Party-Id (A1 defense-in-depth, issue #628).
 * Calls carry the service M2M token (account-service GET /api/v1/accounts/{id} requires SERVICE).
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {

    @GET
    @Path("/{accountId}")
    fun getAccount(@PathParam("accountId") accountId: UUID): Uni<AccountSummary>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountSummary(val id: UUID, val partyId: UUID)

@ApplicationScoped
class AccountServiceClient(@RestClient private val client: AccountServiceRestClient) {

    suspend fun getPartyId(accountId: UUID): UUID? = runCatching {
        client.getAccount(accountId).awaitSuspending().partyId
    }.getOrElse { e ->
        if (e is WebApplicationException &&
            e.response?.status == Response.Status.NOT_FOUND.statusCode
        ) {
            null
        } else {
            throw e
        }
    }
}
