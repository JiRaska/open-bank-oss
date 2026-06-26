// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.web

import jakarta.annotation.security.PermitAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

@Path("/api/v1/config")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Suppress("LongParameterList") // CDI config injection — each property maps to a distinct config key
class ServiceConfigResource(
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "openbank-service")
    private val serviceName: String,

    // ── Rate Limit ──
    @ConfigProperty(name = "openbank.rate-limit.enabled", defaultValue = "true")
    private val rateLimitEnabled: Boolean,

    @ConfigProperty(name = "openbank.rate-limit.max-concurrent-requests", defaultValue = "200")
    private val rateLimitMaxConcurrent: Int,

    // ── Circuit Breaker ──
    @ConfigProperty(name = "openbank.resilience.circuit-breaker.enabled", defaultValue = "false")
    private val cbEnabled: Boolean,

    @ConfigProperty(name = "openbank.resilience.circuit-breaker.request-volume-threshold", defaultValue = "20")
    private val cbRequestVolumeThreshold: Int,

    @ConfigProperty(name = "openbank.resilience.circuit-breaker.failure-ratio", defaultValue = "0.5")
    private val cbFailureRatio: Double,

    @ConfigProperty(name = "openbank.resilience.circuit-breaker.success-threshold", defaultValue = "5")
    private val cbSuccessThreshold: Int,

    @ConfigProperty(name = "openbank.resilience.circuit-breaker.delay-ms", defaultValue = "5000")
    private val cbDelayMs: Long,

    // ── Retry ──
    @ConfigProperty(name = "openbank.resilience.retry.enabled", defaultValue = "false")
    private val retryEnabled: Boolean,

    @ConfigProperty(name = "openbank.resilience.retry.max-retries", defaultValue = "3")
    private val retryMaxRetries: Int,

    @ConfigProperty(name = "openbank.resilience.retry.delay-ms", defaultValue = "200")
    private val retryDelayMs: Long,

    @ConfigProperty(name = "openbank.resilience.retry.jitter-ms", defaultValue = "100")
    private val retryJitterMs: Long,

    // ── Timeout ──
    @ConfigProperty(name = "openbank.resilience.timeout.enabled", defaultValue = "false")
    private val timeoutEnabled: Boolean,

    @ConfigProperty(name = "openbank.resilience.timeout.value-ms", defaultValue = "10000")
    private val timeoutValueMs: Long,
) {
    @GET
    @PermitAll
    fun config(): Response = Response.ok(
        ServiceConfigResponse(
            service = serviceName,
            rateLimit = if (rateLimitEnabled) {
                RateLimitConfig(
                    maxConcurrent = rateLimitMaxConcurrent,
                )
            } else {
                null
            },
            circuitBreaker = if (cbEnabled) {
                CircuitBreakerConfig(
                    requestVolumeThreshold = cbRequestVolumeThreshold,
                    failureRatio = cbFailureRatio,
                    successThreshold = cbSuccessThreshold,
                    delayMs = cbDelayMs,
                )
            } else {
                null
            },
            retry = if (retryEnabled) {
                RetryConfig(
                    maxRetries = retryMaxRetries,
                    delayMs = retryDelayMs,
                    jitterMs = retryJitterMs,
                )
            } else {
                null
            },
            timeout = if (timeoutEnabled) {
                TimeoutConfig(
                    valueMs = timeoutValueMs,
                )
            } else {
                null
            },
        ),
    ).build()
}

data class ServiceConfigResponse(
    val service: String,
    val rateLimit: RateLimitConfig?,
    val circuitBreaker: CircuitBreakerConfig?,
    val retry: RetryConfig?,
    val timeout: TimeoutConfig?,
)

data class RateLimitConfig(val maxConcurrent: Int)

data class CircuitBreakerConfig(
    val requestVolumeThreshold: Int,
    val failureRatio: Double,
    val successThreshold: Int,
    val delayMs: Long,
)

data class RetryConfig(val maxRetries: Int, val delayMs: Long, val jitterMs: Long)

data class TimeoutConfig(val valueMs: Long)
