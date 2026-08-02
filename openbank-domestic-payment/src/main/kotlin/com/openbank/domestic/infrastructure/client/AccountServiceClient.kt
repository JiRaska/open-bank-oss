// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.AccountLookupPort
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
    @Path("/iban/{iban}")
    fun getByIban(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("iban") iban: String,
    ): io.smallrye.mutiny.Uni<AccountDto>

    @GET
    @Path("/{accountId}")
    fun getById(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("accountId") accountId: String,
    ): io.smallrye.mutiny.Uni<AccountDto>
}

data class AccountDto(val id: String, val partyId: String)

private const val HTTP_NOT_FOUND = 404

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
    override suspend fun findPartyByIban(iban: String): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getByIban("Bearer $token", iban).awaitSuspending()
        UUID.fromString(dto.partyId)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status == HTTP_NOT_FOUND) {
            null
        } else {
            log.warnf(
                ex,
                "Account lookup for IBAN %s failed with HTTP %d — treating as external",
                iban,
                ex.response.status,
            )
            null
        }
    } catch (ex: Exception) {
        log.warnf(ex, "Account lookup for IBAN %s failed — treating as external", iban)
        null
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

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findAccountIdByIban(iban: String): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getByIban("Bearer $token", iban).awaitSuspending()
        UUID.fromString(dto.id)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf(ex, "Account id lookup for IBAN %s failed with HTTP %d", iban, ex.response.status)
        }
        null
    } catch (ex: Exception) {
        log.warnf(ex, "Account id lookup for IBAN %s failed", iban)
        null
    }
}
