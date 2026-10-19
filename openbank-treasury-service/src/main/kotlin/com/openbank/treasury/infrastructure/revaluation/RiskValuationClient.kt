// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.revaluation

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.treasury.application.port.out.BookValuation
import com.openbank.treasury.application.port.out.DealFairValue
import com.openbank.treasury.application.port.out.ValuationPort
import com.openbank.treasury.application.port.out.ValuationUnavailableException
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
 * Outbound, read-only client for the risk engine's snapshot list and per-snapshot valuation
 * (ADR-0344 D8), used by the revaluation. Treasury's OWN identity — the named oidc-client `m2m`,
 * Keycloak client `openbank-treasury` — which `risk_rest_ext.rego` grants `risk.snapshot.list` and
 * `risk.valuation.read` by principal id and nothing else. `TreasuryRiskCurvePactConsumerTest`
 * pins both paths; the risk engine's `@PactFolder` replay fails if they move (#2269).
 */
@RegisterRestClient(configKey = "risk-engine")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/risk/snapshots")
@Produces(MediaType.APPLICATION_JSON)
interface RiskValuationRestClient {
    @GET
    fun list(@QueryParam("limit") limit: Int): Uni<SnapshotRunListResponse>

    @GET
    @Path("/{id}/valuation")
    fun valuation(@PathParam("id") id: String): Uni<ValuationResponse>
}

data class SnapshotRunSummaryResponse(val id: UUID, val asOf: String, val status: String)

data class SnapshotRunListResponse(val runs: List<SnapshotRunSummaryResponse> = emptyList())

data class ValuationHeaderResponse(
    val runId: UUID,
    val asOf: String,
    val currency: String,
    val valuationInputHash: String? = null,
)

data class DealValueResponse(
    val dealId: UUID,
    val pv: BigDecimal? = null,
    val clean: BigDecimal? = null,
    val unpriced: String? = null,
)

data class ValuationResponse(
    val header: ValuationHeaderResponse,
    val deals: List<DealValueResponse> = emptyList(),
    val dataGap: String? = null,
)

@ApplicationScoped
class RiskValuationAdapter(@RestClient private val client: RiskValuationRestClient) : ValuationPort {

    override suspend fun latestAsOf(): LocalDate? = tiedOut().maxOfOrNull { LocalDate.parse(it.asOf) }

    /**
     * The valuation of the newest TIED_OUT run whose as-of IS [asOf] — never an older date's, which
     * would post one day's fair value as another's. Any HTTP error or an unreachable engine is a
     * [ValuationUnavailableException] (a data gap), never "no snapshot".
     */
    override suspend fun valuation(asOf: LocalDate): BookValuation? {
        val run = tiedOut().firstOrNull { LocalDate.parse(it.asOf) == asOf } ?: return null
        val v =
            read("read the valuation of snapshot ${run.id}") { client.valuation(run.id.toString()).awaitSuspending() }
        return BookValuation(
            snapshotId = v.header.runId,
            asOf = LocalDate.parse(v.header.asOf),
            currency = v.header.currency,
            inputHash = v.header.valuationInputHash,
            deals = v.deals.associate { it.dealId to DealFairValue(it.dealId, it.pv, it.clean, it.unpriced) },
            dataGap = v.dataGap,
        )
    }

    private suspend fun tiedOut(): List<SnapshotRunSummaryResponse> = read("list the snapshot runs") {
        client.list(LIST_LIMIT).awaitSuspending()
    }.runs.filter { it.status == TIED_OUT }

    private inline fun <T> read(what: String, call: () -> T): T = try {
        call()
    } catch (e: WebApplicationException) {
        throw ValuationUnavailableException("risk engine refused to $what (HTTP ${e.response.status})", e)
    } catch (e: ProcessingException) {
        throw ValuationUnavailableException("risk engine unreachable: could not $what", e)
    }

    private companion object {
        const val LIST_LIMIT = 100
        const val TIED_OUT = "TIED_OUT"
    }
}
