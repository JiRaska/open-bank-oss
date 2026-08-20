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
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outbound client for openbank-ledger-service's GL trial balance.
 *
 * FINREP/COREP reads only the statutory MONTH frozen-evidence endpoint. Ledger rejects DRAFT,
 * missing and legacy HASH_ONLY periods: a report must never silently fall back to a live aggregate.
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
@Path("/api/v1/ledger/periods/MONTH")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {

    @GET
    @Path("/{asOf}/frozen-trial-balance")
    fun getTrialBalance(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>
}

data class TrialBalanceLineResponse(val code: String, val type: String, val net: BigDecimal, val currency: String)

data class ClosedPeriodTrialBalanceResponse(
    val period: String,
    val balanced: Boolean,
    val lines: List<TrialBalanceLineResponse>,
)

@ApplicationScoped
class LedgerAdapter(@RestClient private val client: LedgerRestClient) : LedgerPort {

    override suspend fun getTrialBalance(asOf: LocalDate): List<TrialBalanceLineDto> {
        val response = client.getTrialBalance(asOf.toString()).awaitSuspending()
        return response.lines.map { line ->
            TrialBalanceLineDto(
                code = line.code,
                accountType = line.type,
                net = line.net,
                currency = line.currency,
            )
        }
    }
}
