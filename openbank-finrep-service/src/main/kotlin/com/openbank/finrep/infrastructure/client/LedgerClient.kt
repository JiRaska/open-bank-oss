// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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

@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/ledger")
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
