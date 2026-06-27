// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Signals deprecation of the bespoke `/open-banking/v2` surface per **RFC 8594** (ADR-0090 P4):
 * `Deprecation: true`, a `Sunset` date, and a `Link rel="successor-version"` pointing at the Berlin
 * `/v1` API. The path is **not removed** — admin-ui health probes and any remaining sandbox client
 * still depend on it — but every response now advertises the migration target and the sunset date.
 * Hard removal is gated on that date (tracked in #1118).
 */
@Provider
class BespokeDeprecationFilter : ContainerResponseFilter {

    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        if (!req.uriInfo.path.startsWith("open-banking/")) return
        resp.headers.putSingle("Deprecation", "true")
        resp.headers.putSingle("Sunset", SUNSET)
        resp.headers.add("Link", "</v1>; rel=\"successor-version\"")
    }

    private companion object {
        // RFC 8594 Sunset (HTTP-date). Berlin /v1 is the successor; removal gated on this date.
        const val SUNSET = "Wed, 31 Dec 2031 23:59:59 GMT"
    }
}
