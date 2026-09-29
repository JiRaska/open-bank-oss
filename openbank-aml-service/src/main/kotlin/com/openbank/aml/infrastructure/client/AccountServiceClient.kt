// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

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
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Config-keyed (`quarkus.rest-client.account-service.*`) so the deployment supplies the URL and,
 * in `%prod`, the private-CA mTLS bucket for account-service's 8443 listener. The earlier
 * `RestClientBuilder.baseUri(...)` form read neither, and with `ACCOUNT_SERVICE_URL` unset in gitops
 * the sweep dialled localhost:8100 inside its own pod and resolved nothing.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {
    @GET
    @Path("/{accountId}")
    fun getById(@PathParam("accountId") accountId: String): Uni<AccountDto>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountDto(val id: String = "", val partyId: String = "")

/**
 * Reads the owning party of an account, for the #3413 resolution sweep.
 *
 * Used **only** by the sweep, never on the case-creation path: a case must never fail to be recorded
 * because account-service is unreachable, so resolution happens to a stored row afterwards.
 */
@ApplicationScoped
class AccountServiceClient(@RestClient private val client: AccountServiceRestClient) {
    private val log = Logger.getLogger(AccountServiceClient::class.java)

    /** The party owning [accountId], or `null` if it cannot be determined right now. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        UUID.fromString(client.getById(accountId.toString()).awaitSuspending().partyId)
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
