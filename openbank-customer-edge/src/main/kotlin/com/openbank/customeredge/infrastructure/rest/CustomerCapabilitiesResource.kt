// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/** `live | unavailable` on the wire. A client treats any value it does not know as unavailable. */
enum class CapabilityState(val wire: String) {
    LIVE("live"),
    UNAVAILABLE("unavailable"),
}

/** Why a capability is unavailable. Present only when it is. */
enum class CapabilityUnavailableReason {
    /** No backend for it exists anywhere in the platform. */
    NOT_BUILT,

    /** The backend exists but is not wired to this edge in this environment. */
    NOT_DEPLOYED,

    /** An operator switched it off. */
    DISABLED,
}

data class Capability(val id: String, val state: CapabilityState, val reason: CapabilityUnavailableReason?)

/**
 * ADR-0310 D5 — which app capabilities this environment offers. Pure, so every combination is
 * testable without a running edge.
 *
 * Deliberately computed from CONFIGURATION ONLY. A health probe here would make a product appear
 * and disappear with every blip; a runtime failure stays the per-route 502/503 it already is.
 * Deliberately NOT built on `/surfaces`: those are marketing — consent-gated and
 * dismissal-suppressed — and a customer who withheld marketing consent would lose the product.
 */
object CapabilityCatalog {
    const val LOYALTY = "loyalty"
    const val REFERRALS = "referrals"

    /** Demo-only features in the app with no backend anywhere in this repository. */
    val NOT_BUILT: List<String> = listOf(
        "offers",
        "accept.settlement",
        "allmoney.linked",
        "business.tax",
        "approvals.multisig",
        "rewards.points",
    )

    /** Every id this edge publishes, in the order it publishes them — the spec's closed enum. */
    val IDS: List<String> = listOf(LOYALTY, REFERRALS) + NOT_BUILT

    fun evaluate(
        loyaltyEnabled: Boolean,
        loyaltyServiceUrl: String?,
        referralsEnabled: Boolean,
        referralServiceUrl: String?,
    ): List<Capability> = listOf(
        backed(LOYALTY, loyaltyEnabled, loyaltyServiceUrl),
        backed(REFERRALS, referralsEnabled, referralServiceUrl),
    ) + NOT_BUILT.map { Capability(it, CapabilityState.UNAVAILABLE, CapabilityUnavailableReason.NOT_BUILT) }

    /**
     * Live only when BOTH the backend is wired AND an operator switched it on. A missing backend
     * is reported before a switch: switching on a capability whose service is not deployed must
     * still read as NOT_DEPLOYED, never as live.
     */
    private fun backed(id: String, enabled: Boolean, url: String?): Capability = when {
        url.isNullOrBlank() -> Capability(id, CapabilityState.UNAVAILABLE, CapabilityUnavailableReason.NOT_DEPLOYED)
        !enabled -> Capability(id, CapabilityState.UNAVAILABLE, CapabilityUnavailableReason.DISABLED)
        else -> Capability(id, CapabilityState.LIVE, null)
    }
}

/**
 * `GET /customer/v1/capabilities` (ADR-0310 D5). Authenticated like every customer route, identical
 * for every caller — no party is resolved, so no eligibility decision can leak through it — and
 * cacheable, because the app reads it once per session and on foreground.
 */
@Path("/customer/v1/capabilities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerCapabilitiesResource(
    @ConfigProperty(name = "openbank.edge.capabilities.loyalty.enabled", defaultValue = "false")
    private val loyaltyEnabled: Boolean,
    @ConfigProperty(name = "openbank.edge.capabilities.referrals.enabled", defaultValue = "false")
    private val referralsEnabled: Boolean,
    @ConfigProperty(name = "openbank.edge.loyalty-service-url")
    private val loyaltyServiceUrl: Optional<String>,
    @ConfigProperty(name = "openbank.edge.referral-service-url")
    private val referralServiceUrl: Optional<String>,
) {
    @GET
    @Authorize(action = "customer.capabilities.read")
    @Blocking
    fun capabilities(): Response {
        val capabilities = CapabilityCatalog.evaluate(
            loyaltyEnabled = loyaltyEnabled,
            loyaltyServiceUrl = loyaltyServiceUrl.orElse(null),
            referralsEnabled = referralsEnabled,
            referralServiceUrl = referralServiceUrl.orElse(null),
        )
        val body = mapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "capabilities" to capabilities.map { c ->
                buildMap {
                    put("id", c.id)
                    put("state", c.state.wire)
                    c.reason?.let { put("reason", it.name) }
                }
            },
        )
        return Response.fromResponse(EdgeJson.ok(body)).header("Cache-Control", CACHE_CONTROL).build()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CACHE_CONTROL = "private, max-age=300"
    }
}
