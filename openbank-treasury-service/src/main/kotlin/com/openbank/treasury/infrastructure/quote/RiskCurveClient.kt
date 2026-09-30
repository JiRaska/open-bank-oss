// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.quote

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.CurveSetPort
import com.openbank.treasury.domain.model.CurvePillar
import com.openbank.treasury.domain.model.CurveSetView
import com.openbank.treasury.domain.model.MarketCurve
import com.openbank.treasury.domain.model.QuoteUnavailableException
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound, read-only client for the risk engine's curve sets (ADR-0313 D4), used by the simulated
 * counterparties' quotes (ADR-0315 D9). Treasury's OWN identity — the named oidc-client `m2m`,
 * Keycloak client `openbank-treasury`, the same one that posts to the ledger — which
 * `risk_rest_ext.rego` grants `risk.curve-set.read` by principal id and nothing else. Over the
 * engine's private-CA mTLS listener (8443) in the cluster (`%prod` TLS bucket `risk-authority`).
 * `TreasuryRiskCurvePactConsumerTest` pins the two paths; the risk engine's `@PactFolder` replay
 * fails if they move (#2269).
 */
@RegisterRestClient(configKey = "risk-engine")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/risk/curve-sets")
@Produces(MediaType.APPLICATION_JSON)
interface RiskCurveRestClient {
    @GET
    fun list(@QueryParam("limit") limit: Int): Uni<CurveSetListResponse>

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Uni<CurveSetResponse>
}

data class CurveSetSummaryResponse(val id: UUID, val asOf: String, val provenance: String)

data class CurveSetListResponse(val curveSets: List<CurveSetSummaryResponse> = emptyList())

data class PillarResponse(val date: String, val zeroRate: BigDecimal)

data class CurveResponse(val index: String, val currency: String, val pillars: List<PillarResponse> = emptyList())

data class CurveSetResponse(
    val id: UUID,
    val asOf: String,
    val provenance: String,
    val curves: List<CurveResponse> = emptyList(),
)

@ApplicationScoped
class RiskCurveAdapter(@RestClient private val client: RiskCurveRestClient) : CurveSetPort {

    /**
     * The newest curve set by recording time (the engine lists newest first), with its pillars.
     * An unreachable engine, a refusal (401/403) or any other HTTP error is one outcome for a
     * quote — unavailable (503) — and carries its cause; it is never read as "no curve set".
     */
    override suspend fun latest(): CurveSetView? {
        val newest = read("list the curve sets") { client.list(1).awaitSuspending() }.curveSets.firstOrNull()
            ?: return null
        val set = read("read curve set ${newest.id}") { client.get(newest.id.toString()).awaitSuspending() }
        return CurveSetView(
            id = set.id,
            asOf = LocalDate.parse(set.asOf),
            provenance = set.provenance,
            curves = set.curves.map { c ->
                MarketCurve(c.index, c.currency, c.pillars.map { CurvePillar(LocalDate.parse(it.date), it.zeroRate) })
            },
        )
    }

    private inline fun <T> read(what: String, call: () -> T): T = try {
        call()
    } catch (e: WebApplicationException) {
        throw QuoteUnavailableException("risk engine refused to $what (HTTP ${e.response.status})", e)
    } catch (e: ProcessingException) {
        throw QuoteUnavailableException("risk engine unreachable: could not $what", e)
    }
}
