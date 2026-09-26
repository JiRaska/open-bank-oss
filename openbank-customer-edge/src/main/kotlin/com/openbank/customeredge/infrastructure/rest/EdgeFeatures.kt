// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The client-visible feature flags of THIS edge build (#10281), published as the response header
 * [HEADER] — a comma-separated list, so a future flag is one more token, never a new header.
 *
 * A flag is listed only when the feature is live: the edge accepts it AND the upstream it needs is
 * deployed. `standingorders.replace` needs standing-order-service to honour `replacesStandingOrderId`
 * (an older one would silently create a SECOND order and cancel nothing, i.e. a double debit), so it
 * is switched by config and stays off until that service is rolled out first.
 *
 * Constructor parameter WITHOUT a Kotlin default: a default would make Arc build the bean through the
 * synthetic constructor and never apply the config.
 */
@ApplicationScoped
class EdgeFeatures(
    @ConfigProperty(name = "openbank.edge.features.standingorders-replace", defaultValue = "false")
    private val standingOrdersReplace: Boolean,
) {

    /** True when an edit may name `replacesStandingOrderId`; the same switch gates the route and the header. */
    fun replaceEnabled(): Boolean = standingOrdersReplace

    /** The header value, or null when no flag is live (no header is sent at all). */
    fun headerValue(): String? = buildList {
        if (standingOrdersReplace) add(STANDING_ORDERS_REPLACE)
    }.takeIf { it.isNotEmpty() }?.joinToString(",")

    companion object {
        const val HEADER = "X-Edge-Features"
        const val STANDING_ORDERS_REPLACE = "standingorders.replace"
    }
}

/**
 * Adds [EdgeFeatures.HEADER] to the responses the app's standing-order screens read: the list
 * (`GET /customer/v1/standing-orders`) and the create/replace `POST` on the same path — success and
 * refusal alike, so the app learns the capability from whichever response it already has.
 */
@Provider
class EdgeFeaturesFilter(private val features: EdgeFeatures) : ContainerResponseFilter {

    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        val path = request.uriInfo.path.trim('/')
        val onCollection = path == STANDING_ORDERS_PATH || path.endsWith("/$STANDING_ORDERS_PATH")
        if (!onCollection || (request.method != "GET" && request.method != "POST")) return
        features.headerValue()?.let { response.headers.putSingle(EdgeFeatures.HEADER, it) }
    }

    private companion object {
        const val STANDING_ORDERS_PATH = "customer/v1/standing-orders"
    }
}
