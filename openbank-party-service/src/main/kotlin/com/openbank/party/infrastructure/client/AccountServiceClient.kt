// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.openbank.party.application.port.out.PartyAccountGuardPort
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Typed client for account-service's list-by-party endpoint. `GET /api/v1/accounts?partyId=` is
 * `@RolesAllowed(SERVICE, VIEWER, OPERATOR, ADMIN)`, so the call carries an M2M bearer via
 * [OidcClientRequestReactiveFilter] — the `openbank-services` client_credentials token resolves to
 * ROLE_OPERATOR, which is what actually satisfies the check (nothing in either Keycloak realm
 * grants ROLE_SERVICE, so that constant in the list is dead).
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {

    @GET
    fun listByParty(
        @QueryParam("partyId") partyId: UUID,
        @QueryParam("limit") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Uni<AccountPageBody>
}

data class AccountPageBody(val items: List<AccountSummaryBody> = emptyList(), val pageInfo: PageInfoBody? = null)

data class AccountSummaryBody(val id: UUID? = null, val iban: String? = null, val status: String? = null)

data class PageInfoBody(val nextCursor: String? = null, val hasNextPage: Boolean = false)

/**
 * ADR-0179 merge precondition guard. **Fail-closed by design**: this deliberately does not catch.
 * A transport error, a 401, or a timeout propagates and aborts the merge, because "we could not
 * ask" must never read as "the party owns nothing". Account closure does not verify the balance
 * (ADR-0109 option B), so a wrongly-permitted merge strands funds on a retired identity with
 * nothing downstream to notice.
 *
 * Contrast [com.openbank.party.infrastructure.gdpr.GdprAggregationAdapter], which is best-effort
 * fail-open — appropriate there (a partial export is better than none) and wrong here.
 */
@ApplicationScoped
class AccountServiceClient(@RestClient private val client: AccountServiceRestClient) : PartyAccountGuardPort {

    override suspend fun findOpenAccounts(partyId: UUID): List<String> {
        val open = mutableListOf<String>()
        var cursor: String? = null
        // Paginate to exhaustion: stopping at the first page would let a party with more than
        // PAGE_SIZE accounts pass the guard on a technicality.
        while (true) {
            val page = client.listByParty(partyId, PAGE_SIZE, cursor).awaitSuspending()
            page.items
                .filter { !it.status.equals(STATUS_CLOSED, ignoreCase = true) }
                .forEach { open += it.iban ?: it.id?.toString() ?: UNKNOWN_ACCOUNT }
            val info = page.pageInfo
            if (info == null || !info.hasNextPage || info.nextCursor == null) break
            cursor = info.nextCursor
        }
        return open
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val STATUS_CLOSED = "CLOSED"
        private const val UNKNOWN_ACCOUNT = "<unidentified account>"
    }
}
