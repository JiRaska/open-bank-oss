// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.libs.security.sanitizeForLog
import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter

/**
 * eIDAS QWAC transport-auth + TPP role gate (ADR-0090 P1).
 *
 * Registered as a RESTEasy Reactive [ServerRequestFilter] returning a [Uni], NOT as a plain
 * `ContainerRequestFilter`: the tpp-registry check is a synchronous MP-RestClient call, and a
 * plain filter runs on the Vert.x IO thread ahead of the Kotlin `suspend` resource methods (which
 * reject `@Blocking`), so a real `/v1` POST failed with "Attempting a blocking read on io thread".
 * [filter] stays synchronous and is offloaded to the worker pool by [gate]; the request resumes
 * once the Uni completes, aborted with the returned response if non-null.
 */
@ApplicationScoped
class EidasMtlsFilter(private val tppAuthorizationGuard: TppAuthorizationGuard) {

    private val log = Logger.getLogger(EidasMtlsFilter::class.java)

    // Moved to the shared com.openbank.libs.security.sanitizeForLog (#10907), imported above.

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
    fun gate(ctx: ContainerRequestContext): Uni<Response?> =
        Uni.createFrom().item { filter(ctx) }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())

    /** Blocking gate decision: `null` lets the request through, otherwise the response to abort with. */
    @Suppress("LongMethod")
    fun filter(ctx: ContainerRequestContext): Response? {
        // RESTEasy Reactive's UriInfo.path carries a leading slash ("/v1/..."); normalise once so the
        // prefix checks below match either form (#10997 — without this the gate never ran).
        val path = ctx.uriInfo.path.removePrefix("/")
        // Gate both the deprecated bespoke surface (`open-banking/`) and the Berlin Group XS2A
        // surface (`v1/`, ADR-0090) with the same eIDAS QWAC + TPP role check; the sandbox is open.
        val gated = (path.startsWith("open-banking/") && !path.startsWith("open-banking/sandbox/")) ||
            path.startsWith("v1/")
        if (!gated) return null

        val tppId = ctx.getHeaderString("X-TPP-ID")
            ?: ctx.getHeaderString("SSL-CLIENT-S-DN")

        if (tppId.isNullOrBlank()) {
            log.warnf("Missing TPP identification on path: %s", path.sanitizeForLog())
            return Response.status(401)
                .entity(
                    mapOf(
                        "tppMessages" to listOf(
                            mapOf(
                                "category" to "ERROR",
                                "code" to "CERTIFICATE_MISSING",
                                "text" to "eIDAS QWAC certificate or X-TPP-ID header required",
                            ),
                        ),
                    ),
                ).build()
        }

        val requiredRole = when {
            path.contains("/payments") -> "PISP"
            else -> "AISP"
        }

        val authorization = try {
            tppAuthorizationGuard.requireAuthorized(tppId, requiredRole)
        } catch (e: CircuitBreakerOpenException) {
            log.errorf("TPP registry circuit open for tppId=%s path=%s", tppId.sanitizeForLog(), path.sanitizeForLog())
            return serviceUnavailable()
        } catch (e: Exception) {
            log.errorf(
                e,
                "TPP registry authorization failed for tppId=%s path=%s",
                tppId.sanitizeForLog(),
                path.sanitizeForLog(),
            )
            return serviceUnavailable()
        }

        if (!authorization.authorized) {
            log.warnf(
                "TPP %s rejected for role=%s path=%s",
                tppId.sanitizeForLog(),
                requiredRole,
                path.sanitizeForLog(),
            )
            return Response.status(401)
                .entity(
                    mapOf(
                        "tppMessages" to listOf(
                            mapOf(
                                "category" to "ERROR",
                                "code" to "CERTIFICATE_INVALID",
                                "text" to (authorization.reason ?: "TPP not authorized"),
                            ),
                        ),
                    ),
                ).build()
        }

        ctx.setProperty("tppId", tppId)
        return null
    }

    private fun serviceUnavailable(): Response = Response.status(503)
        .entity(
            mapOf(
                "tppMessages" to listOf(
                    mapOf(
                        "category" to "ERROR",
                        "code" to "SERVICE_UNAVAILABLE",
                        "text" to "TPP registry is temporarily unavailable",
                    ),
                ),
            ),
        ).build()
}
