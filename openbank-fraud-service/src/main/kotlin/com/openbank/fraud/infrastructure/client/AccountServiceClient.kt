// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.client

import com.openbank.fraud.application.port.out.AccountPartyLookupPort
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
 * Resolves the owning party of an account for the ADR-0220 D3.5 fraud-hold signal (issue #2749).
 * Same shape as aml-service's own `AccountServiceClient` of this endpoint (which itself mirrors
 * domestic-payment's). Used only from [com.openbank.fraud.application.usecase.FraudHoldService]'s
 * side-effect path, never on the money-path scoring call itself — a lookup failure must never
 * fail (or even slow) `POST /api/v1/fraud/score`, so it is swallowed and simply skips raising a
 * hold this cycle rather than propagating.
 */
@ApplicationScoped
class AccountServiceClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.account-service.url",
        defaultValue = "http://account-service.accounts.svc:8100",
    )
    private val baseUrl: String,
) : AccountPartyLookupPort {
    private val log = Logger.getLogger(AccountServiceClient::class.java)

    private val httpClient by lazy {
        RestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(AccountServiceRestClient::class.java)
    }

    /** The party owning [accountId], or `null` if it cannot be determined right now. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getById("Bearer $token", accountId.toString()).awaitSuspending()
        UUID.fromString(dto.partyId)
    } catch (ex: WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf(
                ex,
                "[fraud-hold] party lookup for account %s failed with HTTP %d",
                accountId,
                ex.response.status,
            )
        }
        null
    } catch (ex: Exception) {
        log.warnf(ex, "[fraud-hold] party lookup for account %s failed", accountId)
        null
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
