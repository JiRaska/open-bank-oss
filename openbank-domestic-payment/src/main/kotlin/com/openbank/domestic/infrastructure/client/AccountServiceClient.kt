// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.application.port.out.AccountStandingPort
import com.openbank.libs.fincrime.admission.UnavailableCause
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

data class AccountDto(val id: String, val partyId: String, val standing: AccountStandingDto? = null)

/** account-service's `AccountStandingResponse`; the rail reads whether the account may be debited. */
data class AccountStandingDto(val mayDebit: Boolean? = null, val reason: String? = null)

private const val HTTP_NOT_FOUND = 404

@ApplicationScoped
class AccountServiceClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.account-service.url",
        defaultValue = "http://account-service.accounts.svc:8100",
    )
    private val baseUrl: String,
) : AccountLookupPort,
    AccountStandingPort {

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
            log.warnf("Account lookup failed with HTTP %d — treating as external", ex.response.status)
            null
        }
    } catch (ex: Exception) {
        log.warnf("Account lookup failed (%s) — treating as external", ex.javaClass.simpleName)
        null
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getById("Bearer $token", accountId.toString()).awaitSuspending()
        UUID.fromString(dto.partyId)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf("Party lookup failed with HTTP %d", ex.response.status)
        }
        null
    } catch (ex: Exception) {
        log.warnf("Party lookup failed (%s)", ex.javaClass.simpleName)
        null
    }

    /**
     * Standing, read with the account (ADR-0374 D4). Every way of not getting a usable answer is
     * [AccountStandingLookup.Unavailable], never "may debit": an unknown account, a response
     * without `standing` (an account-service older than the field) and a failed call alike.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun debtorStanding(accountId: UUID): AccountStandingLookup = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getById("Bearer $token", accountId.toString()).awaitSuspending()
        val mayDebit = dto.standing?.mayDebit
        if (mayDebit == null) {
            log.warnf("Account lookup answered without standing — treating standing as unavailable")
            AccountStandingLookup.Unavailable(UnavailableCause.MISSING_DATA)
        } else {
            AccountStandingLookup.Known(UUID.fromString(dto.partyId), mayDebit, dto.standing.reason)
        }
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status == HTTP_NOT_FOUND) {
            AccountStandingLookup.Unavailable(UnavailableCause.MISSING_DATA)
        } else {
            log.warnf("Standing lookup failed with HTTP %d", ex.response.status)
            AccountStandingLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
        }
    } catch (ex: Exception) {
        log.warnf("Standing lookup failed (%s)", ex.javaClass.simpleName)
        AccountStandingLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findAccountIdByIban(iban: String): UUID? = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient.getByIban("Bearer $token", iban).awaitSuspending()
        UUID.fromString(dto.id)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        if (ex.response.status != HTTP_NOT_FOUND) {
            log.warnf("Account id lookup failed with HTTP %d", ex.response.status)
        }
        null
    } catch (ex: Exception) {
        log.warnf("Account id lookup failed (%s)", ex.javaClass.simpleName)
        null
    }
}
