// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.client

import com.openbank.finrep.application.port.out.RiskCapitalLookup
import com.openbank.finrep.application.port.out.RiskCapitalPort
import com.openbank.finrep.application.port.out.RiskCapitalResult
import com.openbank.finrep.application.port.out.RiskExposureClass
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
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
 * (ADR-0313 D6). The paths are the risk engine's `RiskResource` (`/api/v1/risk/snapshots`,
 * `/{id}/capital`).
 */
@RegisterRestClient(configKey = "risk-engine")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/risk/snapshots")
@Produces(MediaType.APPLICATION_JSON)
interface RiskEngineRestClient {

    @GET
    fun listRuns(@QueryParam("limit") limit: Int): Uni<SnapshotRunListResponse>

    @GET
    @Path("/{id}/capital")
    fun capital(@PathParam("id") id: String): Uni<CapitalResponse>
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

/**
 * [RiskCapitalPort] over the risk engine. Picks the MOST RECENTLY RECORDED TIED_OUT run whose
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
) : RiskCapitalPort {

    override suspend fun capitalAt(asOf: LocalDate): RiskCapitalLookup {
        if (!enabled) return RiskCapitalLookup.unavailable(DISABLED_REASON)
        val run = client.listRuns(RUN_LIST_LIMIT).awaitSuspending().runs
            .filter { it.status == TIED_OUT && it.asOf == asOf.toString() }
            .maxByOrNull { it.recordedAt }
            ?: return RiskCapitalLookup.unavailable(NO_SNAPSHOT_REASON)
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

    private companion object {
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
