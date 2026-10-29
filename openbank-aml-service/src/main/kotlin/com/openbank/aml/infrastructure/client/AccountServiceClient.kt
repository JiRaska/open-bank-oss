// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import com.openbank.clients.account.AccountClient
import com.openbank.clients.account.AccountHttpException
import com.openbank.clients.account.AccountLookup
import com.openbank.clients.account.api.AccountsApi
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
 *
 * The operations and DTOs are GENERATED from account-service's own `openapi.yaml`
 * (`openbank-client-account`, ADR-0319, #13169); this interface only attaches what is specific to this
 * consumer. Callers go through [AccountServiceClient], never through the generated operations.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface AccountServiceRestClient : AccountsApi

/**
 * Reads the owning party of an account, for the #3413 resolution sweep.
 *
 * Used **only** by the sweep, never on the case-creation path: a case must never fail to be recorded
 * because account-service is unreachable, so resolution happens to a stored row afterwards. Every
 * answer other than a found account with a party resolves to `null`; only a 404 does so silently.
 */
@ApplicationScoped
class AccountServiceClient(@RestClient client: AccountServiceRestClient) {
    private val accounts = AccountClient(client)
    private val log = Logger.getLogger(AccountServiceClient::class.java)

    /** The party owning [accountId], or `null` if it cannot be determined right now. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun findPartyByAccountId(accountId: UUID): UUID? = try {
        when (val lookup = accounts.getAccount(accountId).awaitSuspending()) {
            is AccountLookup.Found ->
                lookup.account.partyId
                    ?: null.also { log.warnf("[party-resolution] account %s carries no partyId", accountId) }
            AccountLookup.NotFound -> null
        }
    } catch (ex: AccountHttpException) {
        log.warnf(ex, "[party-resolution] lookup for account %s failed with HTTP %d", accountId, ex.status)
        null
    } catch (ex: Exception) {
        log.warnf(ex, "[party-resolution] lookup for account %s failed", accountId)
        null
    }
}
