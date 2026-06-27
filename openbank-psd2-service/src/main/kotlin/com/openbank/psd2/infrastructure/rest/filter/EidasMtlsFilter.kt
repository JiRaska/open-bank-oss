// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.jboss.logging.Logger

@Provider
@Priority(Priorities.AUTHENTICATION)
class EidasMtlsFilter(private val tppAuthorizationGuard: TppAuthorizationGuard) : ContainerRequestFilter {

    private val log = Logger.getLogger(EidasMtlsFilter::class.java)

    override fun filter(ctx: ContainerRequestContext) {
        val path = ctx.uriInfo.path
        // Gate both the deprecated bespoke surface (`open-banking/`) and the Berlin Group XS2A
        // surface (`v1/`, ADR-0090) with the same eIDAS QWAC + TPP role check; the sandbox is open.
        val gated = (path.startsWith("open-banking/") && !path.startsWith("open-banking/sandbox/")) ||
            path.startsWith("v1/")
        if (!gated) return

        val tppId = ctx.getHeaderString("X-TPP-ID")
            ?: ctx.getHeaderString("SSL-CLIENT-S-DN")

        if (tppId.isNullOrBlank()) {
            log.warnf("Missing TPP identification on path: %s", path)
            ctx.abortWith(
                Response.status(401)
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
                    ).build(),
            )
            return
        }

        val requiredRole = when {
            path.contains("/payments") -> "PISP"
            else -> "AISP"
        }

        val authorization = try {
            tppAuthorizationGuard.requireAuthorized(tppId, requiredRole)
        } catch (e: CircuitBreakerOpenException) {
            log.errorf("TPP registry circuit open for tppId=%s path=%s", tppId, path)
            ctx.abortWith(serviceUnavailable())
            return
        } catch (e: Exception) {
            log.errorf(e, "TPP registry authorization failed for tppId=%s path=%s", tppId, path)
            ctx.abortWith(serviceUnavailable())
            return
        }

        if (!authorization.authorized) {
            log.warnf("TPP %s rejected for role=%s path=%s", tppId, requiredRole, path)
            ctx.abortWith(
                Response.status(401)
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
                    ).build(),
            )
            return
        }

        ctx.setProperty("tppId", tppId)
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
