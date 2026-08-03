// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {
    @GET
    @Path("/{accountId}")
    fun getById(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("accountId") accountId: String,
    ): Uni<AccountDto>
}

data class AccountDto(val id: String, val partyId: String)

/**
 * Reads the owning party of an account, for the #3413 resolution sweep.
 *
 * Mirrors `openbank-domestic-payment`'s client of the same endpoint. It is used **only** by the
 * sweep, never on the case-creation path: a case must never fail to be recorded because
 * account-service is unreachable, so resolution is something that happens to a stored row
 * afterwards, not a precondition for storing it.
 */
@ApplicationScoped
class AccountServiceClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.account-service.url",
        defaultValue = "http://account-service.accounts.svc:8100",
    )
    private val baseUrl: String,
) {
    private val log = Logger.getLogger(AccountServiceClient::class.java)

    private val httpClient by lazy {
        RestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(AccountServiceRestClient::class.java)
    }

    /** The party owning [accountId], or `null` if it cannot be determined right now. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getById("Bearer $token", accountId.toString()).awaitSuspending()
        UUID.fromString(dto.partyId)
    } catch (ex: WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf(ex, "[party-resolution] lookup for account %s failed with HTTP %d", accountId, ex.response.status)
        }
        null
    } catch (ex: Exception) {
        log.warnf(ex, "[party-resolution] lookup for account %s failed", accountId)
        null
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
