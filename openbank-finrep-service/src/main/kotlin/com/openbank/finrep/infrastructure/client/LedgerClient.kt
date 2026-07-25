// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.client

import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outbound client for openbank-ledger-service's GL trial balance.
 *
 * The root path is `/api/v1/journals` — the trial balance is served by ledger's `LedgerResource`
 * at `GET /api/v1/journals/trial-balance` (`operationId: getTrialBalance`). It is deliberately
 * NOT `/api/v1/ledger/trial-balance`: ledger has no such path (`/api/v1/ledger/...` only roots
 * `close` and `fx-revaluation`), and the fiscal-year-close variant
 * `/api/v1/ledger/close/trial-balance` is a different, non-interchangeable resource.
 *
 * The path is pinned by the consumer-driven pact in
 * [com.openbank.finrep.contract.LedgerTrialBalancePactConsumerTest] (git-pact, ADR-0063), which
 * derives the request path from THESE annotations by reflection and replays it against the pact
 * mock server — so changing the path here fails that test, and ledger's
 * `LedgerPactProviderVerificationTest` replay of the committed pact fails if ledger ever moves
 * the endpoint. Review alone would not catch either direction (#2269).
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {

    @GET
    @Path("/trial-balance")
    fun getTrialBalance(@QueryParam("asOf") asOf: String): Uni<TrialBalanceResponse>
}

data class TrialBalanceLineResponse(val code: String, val type: String, val net: BigDecimal)

data class TrialBalanceResponse(val asOf: String, val balanced: Boolean, val lines: List<TrialBalanceLineResponse>)

@ApplicationScoped
class LedgerAdapter(@RestClient private val client: LedgerRestClient) : LedgerPort {

    override suspend fun getTrialBalance(asOf: LocalDate): List<TrialBalanceLineDto> {
        val response = client.getTrialBalance(asOf.toString()).awaitSuspending()
        return response.lines.map { line ->
            TrialBalanceLineDto(
                code = line.code,
                accountType = line.type,
                net = line.net,
            )
        }
    }
}
