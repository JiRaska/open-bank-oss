// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Fleet-wide **honey endpoints** (ADR-0279 WS2): configured URL paths that no legitimate
 * caller can ever reach — any hit is, by construction, reconnaissance or a confused client —
 * counted, logged, and answered with a plain 404.
 *
 * ## Why this is worth having
 *
 * A honeytoken is the cheapest detection that exists: it needs no baseline, no model, and
 * has effectively zero false positives, because the paths are never linked, never routed,
 * and never documented. One hit says "someone is enumerating this service" — the signal the
 * security-observability gap (no span/log/alert answers "are we being probed") was missing.
 *
 * ## Configuration
 *
 * ```
 * openbank.security.honey.paths=/api/v1/internal/debug,/api/v1/admin/export
 * ```
 *
 * Comma-separated, exact path match against the request path (relative, no leading-slash
 * sensitivity). **Empty/absent property disables the filter entirely** — services opt in by
 * listing their honey paths. The paths themselves must NOT be committed per service in a way
 * that documents them to an attacker reading the repo: prefer environment-specific config
 * (ConfigMap/secret) over application.yaml.
 *
 * ## Response shape
 *
 * 404 with no body — deliberately indistinguishable from "route does not exist". A 403 or a
 * clever error would confirm to the prober that the path is special, which is the one thing
 * a honeypot must never do. The detection happens out-of-band: [SecurityTelemetry.HONEYTOKEN_HITS]
 * counter (tag `path` = the **configured** honey path, low cardinality) plus a WARN log line
 * with a sanitized path and the remote address when available.
 */
@Provider
@ApplicationScoped
@Priority(HoneytokenFilter.PRIORITY)
class HoneytokenFilter @Inject constructor(
    @ConfigProperty(name = "openbank.security.honey.paths")
    private val honeyPaths: Optional<String>,
) : ContainerRequestFilter {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private val configured: List<String> by lazy {
        honeyPaths.orElse("")
            .split(',')
            .map { it.trim().removePrefix("/") }
            .filter { it.isNotEmpty() }
    }

    override fun filter(requestContext: ContainerRequestContext) {
        if (configured.isEmpty()) return
        val requestPath = requestContext.uriInfo.path.trimStart('/')
        val matched = configured.firstOrNull { it == requestPath } ?: return

        if (registryInstance.isResolvable) {
            Counter.builder(SecurityTelemetry.HONEYTOKEN_HITS)
                .description("Hits on configured honey endpoints — any hit is reconnaissance by construction")
                .tags("path", matched)
                .register(registryInstance.get())
                .increment()
        }
        log.warnf(
            "honey endpoint hit: path=%s remote=%s",
            sanitizeForLog(matched),
            sanitizeForLog(requestContext.getHeaderString("X-Forwarded-For") ?: "unknown"),
        )
        requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build())
    }

    companion object {
        private val log = Logger.getLogger(HoneytokenFilter::class.java)

        /** Runs just before authentication: a prober must not need credentials to be counted. */
        const val PRIORITY: Int = Priorities.AUTHENTICATION - 100

        /** Cap on the log-rendered value — long enough to be useful, short enough to bound a log line. */
        private const val LOG_VALUE_MAX = 128

        /**
         * A config value is operator-controlled, but it is rendered into logs on an
         * attacker-triggered path — strip anything non-printable so a crafted value (or a
         * crafted X-Forwarded-For) cannot inject log lines (CRLF) or terminal escapes.
         */
        internal fun sanitizeForLog(value: String): String =
            value.filter { it.isLetterOrDigit() || it in "/-_.:@[]" }.take(LOG_VALUE_MAX)
    }
}
