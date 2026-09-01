// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.client

import com.openbank.finrep.application.port.out.ClosedPeriodDto
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
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
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/ledger/periods")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {

    @GET
    @Path("/MONTH/{asOf}/frozen-trial-balance")
    fun getTrialBalance(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/trial-balance")
    fun getLiveTrialBalance(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    fun listClosedPeriods(
        @QueryParam("from") from: String,
        @QueryParam("to") to: String,
    ): Uni<List<ClosedPeriodResponse>>
}

data class TrialBalanceLineResponse(val code: String, val type: String, val net: BigDecimal, val currency: String)

/**
 * Ledger's frozen trial balance as it arrives on the wire.
 *
 * [balanced] is NULLABLE (issue #6011), although ledger declares it unconditionally and the
 * committed pact pins it. A non-null `Boolean` here is not the stricter choice it looks like:
 * jackson-module-kotlin coerces an absent JSON field to `false` for a non-null Boolean without a
 * default, so a response that lost the field would deserialize into ledger asserting an imbalance —
 * a contract defect reported as an accounting one, with nothing anywhere able to tell them apart.
 * Nullable makes absence its own fact, which `TrialBalanceAssurance` renders as
 * `BalanceVerdict.LEDGER_FLAG_ABSENT`.
 */
data class ClosedPeriodTrialBalanceResponse(
    val period: String,
    val balanced: Boolean?,
    val lines: List<TrialBalanceLineResponse>,
)

data class ClosedPeriodResponse(
    val periodType: String,
    val to: LocalDate,
    val status: String,
    val evidenceState: String,
)

@ApplicationScoped
class LedgerAdapter(@RestClient private val client: LedgerRestClient) : LedgerPort {

    /**
     * Maps the lines AND carries ledger's own `balanced` verdict through (issue #6011). The verdict
     * used to be deserialised here and then silently dropped, so the one check finrep could not
     * make for itself — whether the lines it received are the lines ledger evaluated — was
     * unavailable to it.
     */
    override suspend fun getTrialBalance(asOf: LocalDate): TrialBalanceSnapshot {
        val response = client.getTrialBalance(asOf.toString()).awaitSuspending()
        return response.toSnapshot()
    }

    override suspend fun getLiveTrialBalance(asOf: LocalDate): TrialBalanceSnapshot {
        val response = client.getLiveTrialBalance(asOf.toString()).awaitSuspending()
        return response.toSnapshot()
    }

    private fun ClosedPeriodTrialBalanceResponse.toSnapshot(): TrialBalanceSnapshot = TrialBalanceSnapshot(
        lines = lines.map { line ->
            TrialBalanceLineDto(
                code = line.code,
                accountType = line.type,
                net = line.net,
                currency = line.currency,
            )
        },
        ledgerReportsBalanced = balanced,
    )

    override suspend fun listClosedPeriods(): List<ClosedPeriodDto> =
        client.listClosedPeriods(CLOSED_PERIODS_FROM, CLOSED_PERIODS_TO).awaitSuspending().map {
            ClosedPeriodDto(
                periodType = it.periodType,
                to = it.to,
                status = it.status,
                evidenceState = it.evidenceState,
            )
        }

    private companion object {
        const val CLOSED_PERIODS_FROM = "1970-01-01"
        const val CLOSED_PERIODS_TO = "9999-12-31"
    }
}
