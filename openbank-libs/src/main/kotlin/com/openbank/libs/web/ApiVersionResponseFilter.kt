// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.web

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

@Provider
class ApiVersionResponseFilter(
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "openbank-service")
    private val serviceName: String,

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    private val serviceVersion: String,

    @ConfigProperty(name = "openbank.api.version", defaultValue = "1")
    private val apiVersion: String,
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
                putSingleHeader("Deprecation", "true")
                putSingleHeader("Sunset", "Sat, 31 Dec 2025 23:59:59 GMT")
                putSingleHeader("Link", "</api/v2${req.uriInfo.path}>; rel=\"successor-version\"")
            }
        }
    }

    private fun isDeprecatedPath(path: String): Boolean = false

    private fun jakarta.ws.rs.core.MultivaluedMap<String, Any>.putSingleHeader(key: String, value: String) {
        if (!containsKey(key)) putSingle(key, value)
    }

    companion object {
        const val CORRELATION_ID_KEY = "openbank.correlationId"
    }
}
