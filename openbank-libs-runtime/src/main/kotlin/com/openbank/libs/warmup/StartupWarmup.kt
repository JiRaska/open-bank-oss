// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Warms the JVM before the pod reports ready (#11890).
 *
 * Measured on sanctions-service: every slow request over 48 h was the FIRST request after a pod
 * start (~1.9 s, against 17-60 ms for every other) — the time went to first-use class loading and
 * interpretation of the security/authz filters and of the JSON mapping, not to the database.
 * Argo Rollouts shifts traffic the moment readiness is UP, so a real customer paid that cost on
 * every deploy, rollout step and restart. This bean exercises those paths once, off-request, and
 * [WarmupReadinessCheck] holds readiness DOWN until it has — capped by
 * `openbank.warmup.max-duration`, after which readiness goes UP regardless.
 *
 * `@Startup` because `@ApplicationScoped` is lazy: nothing would otherwise construct this bean.
 */
@Startup
@ApplicationScoped
class StartupWarmup(
    @ConfigProperty(name = "openbank.warmup.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.warmup.max-duration", defaultValue = "20s")
    private val maxDuration: Duration,
    @ConfigProperty(name = "openbank.warmup.json-iterations", defaultValue = "500")
    private val jsonIterations: Int,
    @ConfigProperty(name = "openbank.warmup.http-iterations", defaultValue = "20")
    private val httpIterations: Int,
    @ConfigProperty(name = "openbank.warmup.protected-path", defaultValue = "/api/v1/__warmup-probe")
    private val protectedPath: String,
    private val objectMappers: Instance<ObjectMapper>,
) {
    val gate: WarmupGate = WarmupGate(enabled, maxDuration) { elapsed ->
        LOG.warnf(
            "Startup warm-up still running after %d ms (cap %s); reporting ready anyway",
            elapsed.toMillis(),
            maxDuration,
        )
    }

    fun onStart(@Suppress("UnusedParameter") @Observes event: StartupEvent) {
        if (!enabled) {
            LOG.debug("Startup warm-up disabled (openbank.warmup.enabled=false)")
            return
        }
        gate.start()
        // Off the startup thread: the HTTP self-calls need the server this event precedes.
        Thread({ runAll() }, "openbank-warmup").apply { isDaemon = true }.start()
    }

    internal fun runAll() {
        val t0 = System.nanoTime()
        try {
            val results = WarmupRunner.run(steps()) { r ->
                if (r.succeeded) {
                    LOG.infof("warm-up step %s: %d ms (%s)", r.name, r.millis, r.detail)
                } else {
                    LOG.warnf("warm-up step %s FAILED after %d ms: %s", r.name, r.millis, r.detail)
                }
            }
            LOG.infof(
                "Startup warm-up finished in %d ms (%d/%d steps ok)",
                (System.nanoTime() - t0) / NANOS_PER_MILLI,
                results.count { it.succeeded },
                results.size,
            )
        } finally {
            gate.finish()
        }
    }

    private fun steps(): List<WarmupStep> = listOf(
        WarmupStep("json") { warmJson() },
        WarmupStep("datasource") { warmDatasource() },
        WarmupStep("http-public") { selfCall("/api/v1/info", httpIterations) },
        WarmupStep("http-unauthenticated") { selfCall(protectedPath, httpIterations) },
    )

    private fun warmJson(): String {
        if (!objectMappers.isResolvable) return "no ObjectMapper bean"
        val mapper = objectMappers.get()
        val sample = WarmupPayload(
            id = UUID.randomUUID(),
            at = Instant.now(),
            amount = BigDecimal("12345.67"),
            tags = listOf("a", "b", "c"),
            attributes = mapOf("nested" to mapOf("list" to listOf("x", "y", "z"), "flag" to true)),
        )
        repeat(jsonIterations) {
            val json = mapper.writeValueAsString(sample)
            mapper.readValue(json, WarmupPayload::class.java)
            mapper.readTree(json)
        }
        return "$jsonIterations round-trips"
    }

    private fun warmDatasource(): String = ReactivePoolWarmup.selectOne(STEP_TIMEOUT)

    private fun selfCall(path: String, iterations: Int): String {
        val client = HttpClient.newBuilder().connectTimeout(STEP_TIMEOUT).build()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${httpPort()}$path"))
            .timeout(STEP_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build()
        var status = 0
        repeat(iterations) { status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
        return "$iterations x GET $path -> $status"
    }

    private fun httpPort(): Int {
        val key = if (LaunchMode.current() == LaunchMode.TEST) "quarkus.http.test-port" else "quarkus.http.port"
        val default = if (LaunchMode.current() == LaunchMode.TEST) DEFAULT_TEST_PORT else DEFAULT_PORT
        // Quarkus publishes the actually-bound port as a system property when the configured one
        // is 0 (the fleet's ephemeral test-port convention), so prefer it over the config value.
        System.getProperty(key)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        return ConfigProvider.getConfig().getOptionalValue(key, Int::class.javaObjectType).orElse(default)
    }

    data class WarmupPayload(
        val id: UUID,
        val at: Instant,
        val amount: BigDecimal,
        val tags: List<String>,
        val attributes: Map<String, Any>,
    )

    companion object {
        private val LOG: Logger = Logger.getLogger(StartupWarmup::class.java)
        private val STEP_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_TEST_PORT = 8081
    }
}

/** Readiness stays DOWN until [StartupWarmup] has finished, or its cap has elapsed. */
@Readiness
@ApplicationScoped
class WarmupReadinessCheck(private val warmup: StartupWarmup) : HealthCheck {
    override fun call(): HealthCheckResponse =
        HealthCheckResponse.named("startup-warmup").status(warmup.gate.isReady()).build()
}
