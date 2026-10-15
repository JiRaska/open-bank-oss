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
import java.time.YearMonth

/**
 * Outbound client for openbank-ledger-service's GL trial balance.
 *
 * F01/COREP reads the statutory MONTH frozen CLOSING BALANCE: the cumulative sum of every
 * frozen month up to the reporting date. Ledger rejects DRAFT, missing and legacy HASH_ONLY periods,
 * and any omitted booked movement: a report must never silently fall back to a live aggregate.
 *
 * F02 P&L separately reads `frozen-trial-balance`, which is ONE month's movements. Using the
 * closing balance for F02 would include income and expenses from earlier months and years.
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
    @Path("/MONTH/{asOf}/frozen-closing-balance")
    fun getTrialBalance(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/closing-balance")
    fun getLiveTrialBalance(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/frozen-trial-balance")
    fun getFrozenPeriodMovements(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/trial-balance")
    fun getLivePeriodMovements(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/frozen-year-to-date-trial-balance")
    fun getYearToDateMovements(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

    @GET
    @Path("/MONTH/{asOf}/year-to-date-trial-balance")
    fun getLiveYearToDateMovements(@PathParam("asOf") asOf: String): Uni<ClosedPeriodTrialBalanceResponse>

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
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sourcePeriods: List<String>? = null,
    val sourceContentHashes: List<String>? = null,
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

    override suspend fun getFrozenPeriodMovements(asOf: LocalDate): TrialBalanceSnapshot =
        client.getFrozenPeriodMovements(asOf.toString()).awaitSuspending().toSnapshot()

    override suspend fun getLivePeriodMovements(asOf: LocalDate): TrialBalanceSnapshot =
        client.getLivePeriodMovements(asOf.toString()).awaitSuspending().toSnapshot()

    override suspend fun getYearToDateMovements(asOf: LocalDate): TrialBalanceSnapshot {
        require(asOf == YearMonth.from(asOf).atEndOfMonth()) {
            "A frozen year-to-date FINREP flow requires a month-end reporting date"
        }
        val response = client.getYearToDateMovements(asOf.toString()).awaitSuspending()
        val expectedMonths = (1..asOf.monthValue).map { month -> "MONTH:%04d-%02d".format(asOf.year, month) }
        check(
            response.period == expectedMonths.last() &&
                response.from == LocalDate.of(asOf.year, 1, 1) &&
                response.to == asOf,
        ) { "Ledger returned a year-to-date flow for a different reporting window" }
        val sourceHashes = response.sourceContentHashes.orEmpty()
        check(
            response.sourcePeriods == expectedMonths &&
                sourceHashes.size == expectedMonths.size &&
                sourceHashes.all { it.matches(Regex("[0-9a-f]{64}")) },
        ) { "Ledger year-to-date flow has incomplete frozen monthly evidence lineage" }
        return response.toSnapshot()
    }

    override suspend fun getLiveYearToDateMovements(asOf: LocalDate): TrialBalanceSnapshot {
        val response = client.getLiveYearToDateMovements(asOf.toString()).awaitSuspending()
        check(
            response.period == "MONTH:%04d-%02d".format(asOf.year, asOf.monthValue) &&
                response.from == LocalDate.of(asOf.year, 1, 1) &&
                response.to == asOf,
        ) { "Ledger returned a preview flow for a different year-to-date window" }
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
