// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

@Provider
class SanctionsApiDeprecationFilter(
    @ConfigProperty(name = "openbank.api.sunset-date")
    private val sunsetDate: Optional<String>,
) : ContainerResponseFilter {

    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val path = req.uriInfo.path
        if (!isSupersededV1Check(path)) return
        resp.headers.putSingle("Deprecation", "true")
        sunsetDate.ifPresent { resp.headers.putSingle("Sunset", it) }
        resp.headers.putSingle(
            "Link",
            "<${path.replaceFirst("/api/v1/", "/api/v3/")}>; rel=\"successor-version\"",
        )
    }

    private fun isSupersededV1Check(path: String): Boolean =
        path == V1_ROOT || path in V1_CHECK_PREFIXES || V1_CHECK_ITEM.matches(path)

    companion object {
        private const val V1_ROOT = "/api/v1/sanctions"
        private val V1_CHECK_PREFIXES = setOf(
            "/api/v1/sanctions/screen",
            "/api/v1/sanctions/review",
            "/api/v1/sanctions/hits",
            "/api/v1/sanctions/pending",
        )
        private val V1_CHECK_ITEM = Regex(
            "^/api/v1/sanctions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }
}
