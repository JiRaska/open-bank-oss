// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.application.port.out.PrometheusQueryPort
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.net.URI
import java.time.Instant

@Path("/api/v1")
interface PrometheusRestClient {
    @GET
    @Path("/query")
    fun queryInstant(
        @QueryParam("query") query: String,
    ): java.util.concurrent.CompletableFuture<PrometheusInstantResult>

    @GET
    @Path("/query_range")
    fun queryRange(
        @QueryParam("query") query: String,
        @QueryParam("start") start: String,
        @QueryParam("end") end: String,
        @QueryParam("step") step: String,
    ): java.util.concurrent.CompletableFuture<PrometheusRangeResult>
}

data class PrometheusInstantResult(val status: String, val data: PrometheusInstantData)

data class PrometheusInstantData(val resultType: String, val result: List<PrometheusVectorResult>)

data class PrometheusVectorResult(val metric: Map<String, String>, val value: List<Any>)

data class PrometheusRangeResult(val status: String, val data: PrometheusRangeData)

data class PrometheusRangeData(val resultType: String, val result: List<PrometheusMatrixResult>)

data class PrometheusMatrixResult(val metric: Map<String, String>, val values: List<List<Any>>)

@ApplicationScoped
class PrometheusQueryAdapter(private val config: LivenessSentinelConfig) : PrometheusQueryPort {

    private val log = Logger.getLogger(PrometheusQueryAdapter::class.java)

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
    }

    private val client: PrometheusRestClient by lazy {
        RestClientBuilder.newBuilder()
            .baseUri(URI.create(config.prometheusUrl()))
            .build(PrometheusRestClient::class.java)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun queryInstant(promql: String): Double? = try {
        val result = client.queryInstant(promql).await()
        result.data.result.firstOrNull()
            ?.value?.getOrNull(1)?.toString()?.toDoubleOrNull()
    } catch (ex: Exception) {
        log.warnf("Prometheus query failed for '%s': %s", promql, ex.message)
        null
    }

    // Every series is keyed by its most specific label (falls back to __name__) so a multi-series
    // vector — e.g. one WorkflowLivenessWatchdog gauge per job — becomes a label -> value map the
    // detector activities can iterate without a second Prometheus round-trip.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun queryVector(promql: String): Map<String, Double> = try {
        val result = client.queryInstant(promql).await()
        result.data.result.mapNotNull { series ->
            val label = series.metric.entries.firstOrNull { it.key != "__name__" }?.value
                ?: series.metric["__name__"]
                ?: return@mapNotNull null
            val value = series.value.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            label to value
        }.toMap()
    } catch (ex: Exception) {
        log.warnf("Prometheus vector query failed for '%s': %s", promql, ex.message)
        emptyMap()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun queryRange(
        promql: String,
        start: Instant,
        end: Instant,
        step: String,
    ): List<Pair<Instant, Double>> = try {
        val result = client.queryRange(promql, start.toString(), end.toString(), step).await()
        result.data.result.flatMap { series ->
            series.values.mapNotNull { point ->
                val ts = point.getOrNull(0)?.toString()?.toDoubleOrNull()?.let {
                    Instant.ofEpochMilli((it * MILLIS_PER_SECOND).toLong())
                }
                val value = point.getOrNull(1)?.toString()?.toDoubleOrNull()
                if (ts != null && value != null) ts to value else null
            }
        }
    } catch (ex: Exception) {
        log.warnf("Prometheus range query failed for '%s': %s", promql, ex.message)
        emptyList()
    }
}
