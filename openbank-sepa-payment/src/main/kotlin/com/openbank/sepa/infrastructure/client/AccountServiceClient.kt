// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.AccountLookupPort
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {
    @GET
    @Path("/{accountId}")
    fun getById(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("accountId") accountId: String,
    ): io.smallrye.mutiny.Uni<AccountDto>
}

data class AccountDto(val id: String, val partyId: String)

private const val HTTP_NOT_FOUND = 404

/**
 * The SEPA rail's port of the domestic rail's `AccountServiceClient` (#3274 → #8505). Built
 * through [org.eclipse.microprofile.rest.client.RestClientBuilder] rather than `@RestClient`
 * because the M2M token is fetched per call from the [OidcClient], not injected as a header
 * provider. A failed lookup returns null — the caller still opens the AML case (losing a case
 * over a resolution outage is worse than an imprecise one) but logs it loudly.
 */
@ApplicationScoped
class AccountServiceClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.account-service.url",
        defaultValue = "http://account-service.accounts.svc:8100",
    )
    private val baseUrl: String,
) : AccountLookupPort {

    private val log = Logger.getLogger(AccountServiceClient::class.java)

    private val httpClient by lazy {
        org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder()
            .baseUri(java.net.URI.create(baseUrl))
            .build(AccountServiceRestClient::class.java)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getById("Bearer $token", accountId.toString()).awaitSuspending()
        UUID.fromString(dto.partyId)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf(ex, "Party lookup for account %s failed with HTTP %d", accountId, ex.response.status)
        }
        null
    } catch (ex: Exception) {
        log.warnf(ex, "Party lookup for account %s failed", accountId)
        null
    }
}
