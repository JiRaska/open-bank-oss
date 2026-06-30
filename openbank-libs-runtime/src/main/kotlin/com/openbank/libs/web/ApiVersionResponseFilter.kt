// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.UUID

@Provider
class ApiVersionResponseFilter(
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "openbank-service")
    private val serviceName: String,

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    private val serviceVersion: String,

    @ConfigProperty(name = "openbank.api.version", defaultValue = "1")
    private val apiVersion: String,

    // D6 (ADR-0048): path prefixes whose /vN contract is superseded — set in gitops when /v{N+1} ships.
    // Each entry is matched as a startsWith prefix against the request path.
    // Drives Deprecation + Sunset + Link headers per RFC 8594.
    @ConfigProperty(name = "openbank.api.deprecated-paths")
    private val deprecatedPaths: Optional<List<String>>,

    // RFC 8594 HTTP-date sunset for deprecated paths on this service.
    // Must be at least api_deprecation.min_sunset_window_days (180) ahead (rules.yaml).
    @ConfigProperty(name = "openbank.api.sunset-date")
    private val sunsetDate: Optional<String>,
) : ContainerResponseFilter {

    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        resp.headers.apply {
            // Two version axes (ADR-0048): X-API-Version is the public REST contract major
            // (the URL /api/v{N} boundary); X-Service-Version is the deployable artifact's release SemVer.
            putSingleHeader("X-API-Version", "v$apiVersion")
            putSingleHeader("X-Service-Version", serviceVersion)
            putSingleHeader("X-Service-Name", serviceName)
            putSingleHeader(
                "X-Request-ID",
                req.getHeaderString("X-Request-ID")
                    ?: UUID.randomUUID().toString(),
            )
            putSingleHeader(
                "X-Correlation-ID",
                req.getHeaderString("X-Correlation-ID")
                    ?: req.getProperty(CORRELATION_ID_KEY)?.toString()
                    ?: UUID.randomUUID().toString(),
            )
            if (isDeprecatedPath(req.uriInfo.path)) {
                val nextMajor = apiVersion.toIntOrNull()?.plus(1) ?: apiVersion
                putSingleHeader("Deprecation", "true")
                sunsetDate.ifPresent { putSingleHeader("Sunset", it) }
                putSingleHeader(
                    "Link",
                    "<${req.uriInfo.path.replaceFirst("/api/v$apiVersion/", "/api/v$nextMajor/")}>" +
                        "; rel=\"successor-version\"",
                )
            }
        }
    }

    // Returns true when the request path starts with any of the configured deprecated-path prefixes.
    private fun isDeprecatedPath(path: String): Boolean =
        deprecatedPaths.map { paths -> paths.any { path.startsWith(it) } }.orElse(false)

    private fun jakarta.ws.rs.core.MultivaluedMap<String, Any>.putSingleHeader(key: String, value: String) {
        if (!containsKey(key)) putSingle(key, value)
    }

    companion object {
        const val CORRELATION_ID_KEY = "openbank.correlationId"
    }
}
