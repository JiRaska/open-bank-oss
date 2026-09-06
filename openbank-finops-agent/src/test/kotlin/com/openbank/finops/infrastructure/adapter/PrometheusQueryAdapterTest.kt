// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.infrastructure.config.FinOpsConfig
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant

/**
 * Prometheus is best-effort for this agent: an unreachable server must degrade a sweep, never abort
 * it. That is the whole point of the two `catch` blocks, and it is the branch a live-Prometheus
 * test can never reach — so it is tested here, against a URL that cannot resolve.
 *
 * The two failure returns are deliberately DIFFERENT shapes and both are asserted: `null` from the
 * instant query (which the collectors turn into `0.0` explicitly, so absence is a decision rather
 * than a coincidence) and an EMPTY list from the range query.
 */
class PrometheusQueryAdapterTest {

    private fun adapterFor(url: String): PrometheusQueryAdapter {
        val config = mockk<FinOpsConfig>()
        every { config.prometheusUrl() } returns url
        return PrometheusQueryAdapter(config)
    }

    @Test
    fun `an unreachable Prometheus yields null rather than throwing out of the activity`(): Unit = runBlocking {
        val result = adapterFor("http://prometheus.invalid.example:9090").queryInstant("up")

        assertThat(result).isNull()
    }

    @Test
    fun `an unreachable Prometheus yields an empty range rather than throwing`(): Unit = runBlocking {
        val result = adapterFor("http://prometheus.invalid.example:9090")
            .queryRange("up", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"), "5m")

        assertThat(result).isEmpty()
    }

    @Test
    fun `a malformed base URI is swallowed too, so bad config degrades instead of crashing`(): Unit = runBlocking {
        // The lazy client is built inside the same try/catch, so even a URI that cannot be parsed
        // reaches the caller as "no data" rather than as an exception mid-sweep.
        val adapter = adapterFor("not a uri at all")

        assertThat(adapter.queryInstant("up")).isNull()
        assertThat(adapter.queryRange("up", Instant.EPOCH, Instant.EPOCH, "1m")).isEmpty()
    }

    @Test
    fun `a real instant response is parsed to the sample VALUE, not its timestamp`(): Unit = runBlocking {
        // Prometheus returns `value: [<unix ts>, "<value>"]`. Reading index 0 would return epoch
        // seconds - a plausible large number every threshold detector would fire on.
        withServer(
            """{"status":"success","data":{"resultType":"vector","result":""" +
                """[{"metric":{"__name__":"up"},"value":[1767225600,"42.5"]}]}}""",
        ) { url ->
            assertThat(adapterFor(url).queryInstant("up")).isEqualTo(42.5)
        }
    }

    @Test
    fun `an empty instant vector reads as null, so the collector can decide the fallback`(): Unit = runBlocking {
        withServer("""{"status":"success","data":{"resultType":"vector","result":[]}}""") { url ->
            assertThat(adapterFor(url).queryInstant("up")).isNull()
        }
    }

    @Test
    fun `a range response flattens every series into timestamped points`(): Unit = runBlocking {
        withServer(
            """{"status":"success","data":{"resultType":"matrix","result":[""" +
                """{"metric":{"i":"a"},"values":[[1767225600,"10"],[1767225660,"20"]]},""" +
                """{"metric":{"i":"b"},"values":[[1767225600,"30"]]}]}}""",
        ) { url ->
            val points = adapterFor(url).queryRange("up", Instant.EPOCH, Instant.EPOCH, "1m")

            assertThat(points).containsExactly(
                Instant.ofEpochSecond(1_767_225_600) to 10.0,
                Instant.ofEpochSecond(1_767_225_660) to 20.0,
                Instant.ofEpochSecond(1_767_225_600) to 30.0,
            )
        }
    }

    @Test
    fun `a range point that is not numeric is dropped rather than defaulted to zero`(): Unit = runBlocking {
        withServer(
            """{"status":"success","data":{"resultType":"matrix","result":[""" +
                """{"metric":{},"values":[[1767225600,"NaN-ish"],[1767225660,"5"]]}]}}""",
        ) { url ->
            assertThat(adapterFor(url).queryRange("up", Instant.EPOCH, Instant.EPOCH, "1m"))
                .containsExactly(Instant.ofEpochSecond(1_767_225_660) to 5.0)
        }
    }

    private suspend fun withServer(body: String, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
