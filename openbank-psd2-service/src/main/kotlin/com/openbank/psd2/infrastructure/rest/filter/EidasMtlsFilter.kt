// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.libs.security.sanitizeForLog
import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter

/**
 * eIDAS QWAC transport-auth gate (ADR-0090 P1), gating both the deprecated bespoke surface
 * (`open-banking/`, sandbox excepted) and the Berlin Group XS2A surface (`v1/`).
 *
 * **Runs on the IO thread by default (#11008-follow-up, issue TBD).** The Berlin resource methods
 * ([com.openbank.psd2.infrastructure.rest.BerlinConsentResource], `BerlinPisResource`, …) are
 * Kotlin `suspend fun`s, which RESTEasy Reactive treats as non-blocking; a `@ServerRequestFilter`
 * inherits the thread of the resource method it guards, so it never gets a worker thread of its
 * own. [TppAuthorizationGuard.requireAuthorized] is a SYNCHRONOUS (blocking) REST-client call, so
 * it CANNOT run inline here — once #10997 made this filter actually execute on `/v1` traffic, a
 * real (non-mocked) registry round trip would throw `BlockingOperationNotAllowedException` before
 * ever reaching the resource. `@Blocking` cannot fix this: Quarkus refuses `@Blocking` on a
 * `suspend` resource method ("Suspendable @Blocking methods are not supported yet"), and marking
 * the whole class/Application blocking would push every non-blocking endpoint onto worker threads
 * needlessly. Instead the registry call is explicitly offloaded onto
 * [Infrastructure.getDefaultWorkerPool] (same pattern as
 * `CopilotChatResource.chatStream`'s `runSubscriptionOn`, which carries the CDI/SmallRye Context
 * Propagation request context onto the worker thread), and the filter returns `Uni<Response?>` —
 * a `null` item lets RESTEasy Reactive continue the filter chain, a non-null item aborts the
 * request with that response, exactly mirroring the old `ctx.abortWith(...)` calls.
 */
@ApplicationScoped
class EidasMtlsFilter(private val tppAuthorizationGuard: TppAuthorizationGuard) {

    private val log = Logger.getLogger(EidasMtlsFilter::class.java)

    @ServerRequestFilter
    fun filter(ctx: ContainerRequestContext): Uni<Response?> {
        // RESTEasy Reactive's UriInfo.path carries a leading slash ("/v1/..."); normalise once so the
        // prefix checks below match either form (#10997 — without this the gate never ran).
        val path = ctx.uriInfo.path.removePrefix("/")
        // Gate both the deprecated bespoke surface (`open-banking/`) and the Berlin Group XS2A
        // surface (`v1/`, ADR-0090) with the same eIDAS QWAC + TPP role check; the sandbox is open.
        val gated = (path.startsWith("open-banking/") && !path.startsWith("open-banking/sandbox/")) ||
            path.startsWith("v1/")
        if (!gated) return Uni.createFrom().nullItem()

        val tppId = ctx.getHeaderString("X-TPP-ID")
            ?: ctx.getHeaderString("SSL-CLIENT-S-DN")

        if (tppId.isNullOrBlank()) {
            log.warnf("Missing TPP identification on path: %s", path.sanitizeForLog())
            return Uni.createFrom().item(certificateMissing())
        }

        val requiredRole = when {
            path.contains("/payments") -> "PISP"
            else -> "AISP"
        }

        // The registry round trip is blocking (synchronous REST client); it must not run on the
        // IO thread the resource's suspend function otherwise keeps this filter on.
        return Uni.createFrom().item<Response?> {
            try {
                val authorization = tppAuthorizationGuard.requireAuthorized(tppId, requiredRole)
                if (!authorization.authorized) {
                    log.warnf(
                        "TPP %s rejected for role=%s path=%s",
                        tppId.sanitizeForLog(),
                        requiredRole,
                        path.sanitizeForLog(),
                    )
                    certificateInvalid(authorization.reason)
                } else {
                    ctx.setProperty("tppId", tppId)
                    null
                }
            } catch (e: CircuitBreakerOpenException) {
                log.errorf(
                    "TPP registry circuit open for tppId=%s path=%s",
                    tppId.sanitizeForLog(),
                    path.sanitizeForLog(),
                )
                serviceUnavailable()
            } catch (e: Exception) {
                log.errorf(
                    e,
                    "TPP registry authorization failed for tppId=%s path=%s",
                    tppId.sanitizeForLog(),
                    path.sanitizeForLog(),
                )
                serviceUnavailable()
            }
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
    }

    private fun certificateMissing(): Response = Response.status(401)
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

    private fun certificateInvalid(reason: String?): Response = Response.status(401)
        .entity(
            mapOf(
                "tppMessages" to listOf(
                    mapOf(
                        "category" to "ERROR",
                        "code" to "CERTIFICATE_INVALID",
                        "text" to (reason ?: "TPP not authorized"),
                    ),
                ),
            ),
        ).build()

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
