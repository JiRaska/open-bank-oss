// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.client

import com.openbank.finrep.application.port.out.RiskCapitalLookup
import com.openbank.finrep.application.port.out.RiskCapitalPort
import com.openbank.finrep.application.port.out.RiskCapitalResult
import com.openbank.finrep.application.port.out.RiskExposureClass
import com.openbank.finrep.application.port.out.RiskHqlaLine
import com.openbank.finrep.application.port.out.RiskInflowLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityPort
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.application.port.out.RiskOutflowLine
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outbound, read-only client for openbank-risk-engine's snapshot list and Pillar 1 capital result
 * (ADR-0313 D6), as finrep's own identity (named oidc-client `m2m`, Keycloak client
 * `openbank-finrep`), which the risk engine grants risk.snapshot.read by identity. The paths are the
 * risk engine's `RiskResource`; `RiskEngineCapitalPactConsumerTest` pins them and the risk engine's
 * provider replay of the committed pact fails if they move (#2269).
 */
@RegisterRestClient(configKey = "risk-engine")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/risk/snapshots")
@Produces(MediaType.APPLICATION_JSON)
interface RiskEngineRestClient {

    @GET
    fun listRuns(@QueryParam("limit") limit: Int): Uni<SnapshotRunListResponse>

    @GET
    @Path("/{id}/capital")
    fun capital(@PathParam("id") id: String): Uni<CapitalResponse>

    @GET
    @Path("/{id}/liquidity")
    fun liquidity(@PathParam("id") id: String): Uni<LiquidityResponse>
}

data class SnapshotRunSummaryResponse(val id: String, val asOf: String, val recordedAt: String, val status: String)

data class SnapshotRunListResponse(val runs: List<SnapshotRunSummaryResponse>)

data class ExposureClassResponse(val exposureClass: String, val ead: BigDecimal, val rwa: BigDecimal)

data class CurrencyCapitalResponse(
    val currency: String,
    val classes: List<ExposureClassResponse>,
    val totalRwa: BigDecimal,
)

data class UnclassifiedBalanceResponse(val glAccountCode: String?)

data class CapitalResponse(
    val runId: String,
    val asOf: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currencies: List<CurrencyCapitalResponse>,
    val total: CurrencyCapitalResponse?,
    val unclassified: List<UnclassifiedBalanceResponse>,
)

/** One HQLA line of the risk engine's LCR (only the fields C 72.00 reads). */
data class HqlaLineResponse(
    val level: String,
    val marketValue: BigDecimal,
    val haircut: BigDecimal,
    val afterHaircut: BigDecimal,
)

/** Level sums are after haircut and BEFORE the Level 2 caps (the risk engine's d238 Annex 1 ¶5 inputs). */
data class HqlaResponse(
    val lines: List<HqlaLineResponse>,
    val level1: BigDecimal,
    val level2a: BigDecimal,
    val level2b: BigDecimal,
)

/** One LCR outflow line (only the fields C 73.00 reads); `factorKey` names the run-off rate applied. */
data class OutflowLineResponse(
    val factorKey: String,
    val amount: BigDecimal,
    val factor: BigDecimal,
    val weighted: BigDecimal,
)

data class LcrResponse(
    val hqla: HqlaResponse,
    val outflows: List<OutflowLineResponse> = emptyList(),
    val totalOutflows: BigDecimal? = null,
    val inflows: List<OutflowLineResponse> = emptyList(),
    val totalInflows: BigDecimal? = null,
    val inflowCap: BigDecimal? = null,
    val cappedInflows: BigDecimal? = null,
    val inflowCapBinding: Boolean? = null,
)

data class CurrencyLiquidityResponse(val currency: String, val lcr: LcrResponse)

data class LiquidityResponse(
    val runId: String,
    val asOf: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currencies: List<CurrencyLiquidityResponse>,
    val total: CurrencyLiquidityResponse?,
    val unclassified: List<UnclassifiedBalanceResponse>,
)

/**
 * [RiskCapitalPort] and [RiskLiquidityPort] over the risk engine. Picks the MOST RECENTLY RECORDED TIED_OUT run whose
 * `asOf` is exactly the report date: an UNTIED run is never a source (ADR-0314 D3), and a run for
 * another date is not this report's. Behind `openbank.finrep.risk-engine.enabled` (default off)
 * until the finrep identity, its risk-engine read grant and the network edge are deployed: while
 * off, C 02.00 renders every credit-risk row as a data gap saying so, never a 5xx.
 */
@ApplicationScoped
class RiskEngineCapitalAdapter(
    @RestClient private val client: RiskEngineRestClient,
    @ConfigProperty(name = "openbank.finrep.risk-engine.enabled", defaultValue = "false")
    private val enabled: Boolean,
) : RiskCapitalPort,
    RiskLiquidityPort {

    override suspend fun capitalAt(asOf: LocalDate): RiskCapitalLookup {
        if (!enabled) return RiskCapitalLookup.unavailable(DISABLED_REASON)
        val run = tiedOutRunAt(asOf) ?: return RiskCapitalLookup.unavailable(NO_SNAPSHOT_REASON)
        val c = client.capital(run.id).awaitSuspending()
        return RiskCapitalLookup.found(
            RiskCapitalResult(
                runId = c.runId,
                asOf = LocalDate.parse(c.asOf),
                parameterSetId = c.parameterSetId,
                parameterSetVersion = c.parameterSetVersion,
                currency = c.total?.currency,
                classes = c.total?.classes.orEmpty().map { RiskExposureClass(it.exposureClass, it.ead, it.rwa) },
                totalRwa = c.total?.totalRwa,
                currencyCount = c.currencies.size,
                unclassifiedBalances = c.unclassified.size,
            ),
        )
    }

    override suspend fun liquidityAt(asOf: LocalDate): RiskLiquidityLookup {
        if (!enabled) return RiskLiquidityLookup.unavailable(DISABLED_REASON_LIQUIDITY)
        val run = tiedOutRunAt(asOf) ?: return RiskLiquidityLookup.unavailable(NO_SNAPSHOT_REASON_LIQUIDITY)
        val l = client.liquidity(run.id).awaitSuspending()
        val lcr = l.total?.lcr
        val hqla = lcr?.hqla
        return RiskLiquidityLookup.found(
            RiskLiquidityResult(
                runId = l.runId,
                asOf = LocalDate.parse(l.asOf),
                parameterSetId = l.parameterSetId,
                parameterSetVersion = l.parameterSetVersion,
                currency = l.total?.currency,
                lines = hqla?.lines.orEmpty().map {
                    RiskHqlaLine(it.level, it.marketValue, it.haircut, it.afterHaircut)
                },
                level1 = hqla?.level1,
                level2a = hqla?.level2a,
                level2b = hqla?.level2b,
                currencyCount = l.currencies.size,
                unclassifiedBalances = l.unclassified.size,
                outflows = lcr?.outflows.orEmpty().map {
                    RiskOutflowLine(it.factorKey, it.amount, it.factor, it.weighted)
                },
                totalOutflows = lcr?.totalOutflows,
                inflows = lcr?.inflows.orEmpty().map {
                    RiskInflowLine(it.factorKey, it.amount, it.factor, it.weighted)
                },
                totalInflows = lcr?.totalInflows,
                inflowCap = lcr?.inflowCap,
                cappedInflows = lcr?.cappedInflows,
                inflowCapBinding = lcr?.inflowCapBinding,
            ),
        )
    }

    /**
     * The one run-selection rule for every risk-engine template: the MOST RECENTLY RECORDED
     * TIED_OUT run at exactly [asOf], or null.
     */
    private suspend fun tiedOutRunAt(asOf: LocalDate): SnapshotRunSummaryResponse? =
        client.listRuns(RUN_LIST_LIMIT).awaitSuspending().runs
            .filter { it.status == TIED_OUT && it.asOf == asOf.toString() }
            .maxByOrNull { it.recordedAt }

    private companion object {
        const val DISABLED_REASON_LIQUIDITY =
            "The risk-engine read is not enabled for finrep (openbank.finrep.risk-engine.enabled), so no " +
                "liquid-asset figure is available."
        const val NO_SNAPSHOT_REASON_LIQUIDITY =
            "No TIED_OUT risk-engine snapshot exists at the report date, so no liquid-asset figure can be stated."
        const val TIED_OUT = "TIED_OUT"
        const val DISABLED_REASON =
            "The risk-engine read is not enabled for finrep (openbank.finrep.risk-engine.enabled), so no " +
                "Pillar 1 figure is available."
        const val NO_SNAPSHOT_REASON =
            "No TIED_OUT risk-engine snapshot exists at the report date, so no Pillar 1 figure can be stated."

        /** The risk engine's maximum page; runs are listed newest first. */
        const val RUN_LIST_LIMIT = 100
    }
}
