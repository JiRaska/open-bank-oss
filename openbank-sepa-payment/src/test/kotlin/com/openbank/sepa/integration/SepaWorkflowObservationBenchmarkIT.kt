// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Manual, isolated HTTP write probe. Run twice with the observation flag off and on. */
@QuarkusTest
@QuarkusTestResource(SepaWorkflowObservationBenchmarkIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
class SepaWorkflowObservationBenchmarkIT {
    private data class WriteResult(
        val serviceMs: Long,
        val queueMs: Long,
        val endToEndMs: Long,
        val completedAt: Long,
        val status: Int,
    )

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("events-out")

        override fun stop() = Unit
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SEPA_OBSERVATION_BENCHMARK", matches = "true")
    @TestSecurity(user = "00000000-0000-0000-0000-000000008355", roles = ["ROLE_PAYMENTS"])
    fun `authenticated payment write at baseline and tenfold request rate`() {
        val enabled = ConfigProvider.getConfig()
            .getValue("openbank.sepa.workflow-observations.enabled", Boolean::class.java)
        val baselineRate = System.getenv("SEPA_BENCHMARK_BASE_RPS")?.toIntOrNull() ?: 5
        val durationSeconds = System.getenv("SEPA_BENCHMARK_SECONDS")?.toIntOrNull() ?: 10
        require(baselineRate in 1..20 && durationSeconds in 5..60)
        val executor = Executors.newFixedThreadPool(32)
        try {
            // Warm the same authenticated route before either measurement window.
            repeat(10) { assertThat(createPayment().status).isEqualTo(201) }
            for (rate in listOf(baselineRate, baselineRate * 10)) {
                val tasks = ArrayList<java.util.concurrent.Future<WriteResult>>(rate * durationSeconds)
                val start = System.nanoTime()
                repeat(rate * durationSeconds) { index ->
                    val due = start + index * 1_000_000_000L / rate
                    val remaining = due - System.nanoTime()
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                    tasks += executor.submit(Callable { createPayment(due) })
                }
                val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
                val latencies = results.map { it.serviceMs }.sorted()
                val queueTimes = results.map { it.queueMs }.sorted()
                val endToEndTimes = results.map { it.endToEndMs }.sorted()
                val failures = results.count { it.status != 201 }
                val p50 = latencies[(latencies.size * 0.50).toInt()]
                val p95 = latencies[(latencies.size * 0.95).toInt()]
                val p99 = latencies[(latencies.size * 0.99).toInt()]
                val queueP95 = queueTimes[(queueTimes.size * 0.95).toInt()]
                val endToEndP95 = endToEndTimes[(endToEndTimes.size * 0.95).toInt()]
                val elapsed = results.maxOf { it.completedAt } - start
                val achievedRps = results.size * 1_000_000_000.0 / elapsed
                println(
                    "SEPA_OBSERVATION_BENCH enabled=$enabled targetRps=$rate durationSeconds=$durationSeconds " +
                        "requests=${results.size} failures=$failures " +
                        "achievedRps=${String.format(Locale.ROOT, "%.1f", achievedRps)} " +
                        "serviceP50Ms=$p50 serviceP95Ms=$p95 serviceP99Ms=$p99 " +
                        "queueP95Ms=$queueP95 endToEndP95Ms=$endToEndP95",
                )
                assertThat(failures).isZero()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createPayment(scheduledAt: Long = System.nanoTime()): WriteResult {
        val started = System.nanoTime()
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "benchmark-${UUID.randomUUID()}")
            .body(
                """{
                  "type":"SCT",
                  "debtorAccountId":"${UUID.randomUUID()}",
                  "debtorIban":"CZ6508000000192000145399",
                  "debtorName":"Synthetic Benchmark Debtor",
                  "creditorIban":"DE89370400440532013000",
                  "creditorName":"Synthetic Benchmark Creditor",
                  "creditorBic":"COBADEFFXXX",
                  "amount":10.00,
                  "currency":"EUR",
                  "endToEndId":null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sepa-payments")
        val completed = System.nanoTime()
        return WriteResult(
            serviceMs = TimeUnit.NANOSECONDS.toMillis(completed - started),
            queueMs = TimeUnit.NANOSECONDS.toMillis((started - scheduledAt).coerceAtLeast(0)),
            endToEndMs = TimeUnit.NANOSECONDS.toMillis((completed - scheduledAt).coerceAtLeast(0)),
            completedAt = completed,
            status = response.statusCode,
        )
    }
}
