// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * The one decision both account provider replays share for tax-reporting's pact (ADR-0340 B1):
 * which interactions must be replayed as the verified `service-account-openbank-tax-reporting`
 * identity, and what that identity looks like.
 *
 * It lives here, not in each class, because the two classes drifted: #15652 taught only the
 * `@PactFolder` twin that the GSFT deposit roster needs this identity, so the PR replay was green
 * while the `@PactBroker` twin, whose result is the one can-i-deploy reads, replayed GSFT as
 * `pact-verifier`, answered 403, and published a failed verification that blocked
 * tax-reporting's deploy.
 */
internal object TaxReportingReplayIdentity {
    private val DESCRIPTION_MARKERS = listOf("CRS FATCA", "GSFT deposit roster as of")

    fun appliesTo(description: String): Boolean = DESCRIPTION_MARKERS.any { description.contains(it) }

    /** Name bound to the service-account username with `azp` present, as rego requires (#12448). */
    fun identity(): QuarkusSecurityIdentity {
        val name = "service-account-openbank-tax-reporting"
        val jwt = object : JsonWebToken {
            private val claims = mapOf(
                "azp" to "openbank-tax-reporting",
                "preferred_username" to name,
                "sub" to "tax-reporting-sa-subject",
            )

            override fun getName(): String = name

            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        return QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole("ROLE_API").build()
    }
}
