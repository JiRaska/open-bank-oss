// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.util.Optional

/**
 * #12448: `GET /intake/financial-health` admits the customer-edge by its CLIENT identity (`azp` +
 * `service-account-<azp>`), never by the principal name. The refusal returns before any collaborator
 * is touched, and a permitted caller with no party header gets 400 — so 400 vs 403 is the observable
 * that says which side of the caller check a token landed on.
 */
class CustomerFinancialHealthCallerTest {

    private val edge = "service-account-openbank-edge"

    private val config = CustomerIntakeConfig(
        enabled = true,
        callerPrincipal = Optional.of(edge),
        jurisdiction = "CZ",
        productType = "CONSUMER_CREDIT",
        currency = "CZK",
        nominalAnnualRate = Optional.of(BigDecimal("0.079")),
        minAmount = BigDecimal("5000"),
        maxAmount = BigDecimal("1000000"),
        minTermMonths = 6,
        maxTermMonths = 120,
    )

    private fun jwt(azp: String?, username: String?, sub: String? = "sa-subject"): SecurityIdentity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(TestJwt(mapOf("azp" to azp, "preferred_username" to username, "sub" to sub)))
            .build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as String? ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    private fun statusFor(who: SecurityIdentity): Int =
        CustomerFinancialHealthResource(mockk(), mockk(), mockk(), config, who)
            .health(null).await().indefinitely().status

    @Test
    fun `the edge's own service-account token passes the caller check`() {
        assertThat(statusFor(jwt("openbank-edge", edge))).isEqualTo(400)
    }

    @Test
    fun `the edge's principal name on a token issued to another client is refused`() {
        assertThat(statusFor(jwt("openbank-admin-ui", edge))).isEqualTo(403)
    }

    @Test
    fun `a human token issued through the edge client is refused`() {
        assertThat(statusFor(jwt("openbank-edge", "alice"))).isEqualTo(403)
    }

    @Test
    fun `a non-JWT principal carrying the edge's name is refused`() {
        assertThat(statusFor(QuarkusSecurityIdentity.builder().setPrincipal(Principal { edge }).build())).isEqualTo(403)
    }
}
