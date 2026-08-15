// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider

/**
 * Generated server stubs correctly model If-Match as required. Reject it before Kotlin's generated
 * non-null parameter guard so an absent header retains the RFC 6585 428 response promised by v2.
 */
@Provider
@Priority(Priorities.USER)
class CatalogPreconditionRequestFilter : ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        val path = context.uriInfo.path.trimStart('/')
        val guarded =
            (context.method == "PUT" && REVISION_PATH.matches(path)) ||
                (context.method == "POST" && PUBLISH_PATH.matches(path))
        if (guarded && context.getHeaderString("If-Match") == null) {
            throw CatalogPreconditionRequiredException("If-Match is required")
        }
    }

    private companion object {
        val REVISION_PATH = Regex("api/v2/offerings/[^/]+/revisions/[^/]+")
        val PUBLISH_PATH = Regex("api/v2/offerings/[^/]+/revisions/[^/]+/publish")
    }
}
