// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.infrastructure.rest

import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * #10486 batch 6: `GET /api/v1/incentives/offers/{id}` admits ROLE_API so campaign-service can read
 * an offer as its OWN principal once the shared `openbank-services` client loses ROLE_OPERATOR.
 * ROLE_API is held by EVERY service account and incentive-service has no OPA sidecar, so the
 * named-caller check is what refuses the rest.
 */
class OfferReadCallerGuardTest {

    /**
     * #12448: a `service-account-<client>` name becomes that client's OWN verified service-account
     * token (`azp` = client, `preferred_username` = the name, `sub` set) — the only shape the guard
     * admits. Any other name stays a plain, non-JWT principal.
     */
    private fun identity(name: String, vararg roles: String) = if (name.startsWith("service-account-")) {
        jwtIdentity(name.removePrefix("service-account-"), name, *roles)
    } else {
        QuarkusSecurityIdentity.builder().setPrincipal(QuarkusPrincipal(name)).addRoles(roles.toSet()).build()
    }

    private fun jwtIdentity(azp: String?, username: String?, vararg roles: String, sub: String? = "sa-subject") =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(TestJwt(mapOf("azp" to azp, "preferred_username" to username, "sub" to sub)))
            .addRoles(roles.toSet())
            .build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `staff role on an unrelated machine token cannot bypass client binding`() {
        val machine = jwtIdentity(
            "openbank-unrelated",
            "service-account-openbank-unrelated",
            "ROLE_OPERATOR",
            "ROLE_API",
        )
        assertThatThrownBy { requireNamedOfferReader(machine) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { requireNamedOfferReader(identity("u-staff", "ROLE_OPERATOR")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `campaign's own principal with ROLE_API only may read an offer`() {
        assertThatCode { requireNamedOfferReader(identity("service-account-openbank-campaign", "ROLE_API")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `another service account holding ROLE_API is refused`() {
        assertThatThrownBy { requireNamedOfferReader(identity("service-account-openbank-mcp", "ROLE_API")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        assertThatThrownBy { requireNamedOfferReader(identity("service-account-openbank-services", "ROLE_API")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `an operator keeps the pre-#10486 behaviour`() {
        assertThatCode { requireNamedOfferReader(jwtIdentity("openbank-admin-ui", "u-staff", "ROLE_OPERATOR")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `only the offer read admits ROLE_API, every other operation stays operator-only`() {
        val methods = IncentiveResource::class.java.declaredMethods
        val getOffer = methods.first { it.name == "getOffer" }
        assertThat(getOffer.getAnnotation(RolesAllowed::class.java).value).containsExactlyInAnyOrder(
            "ROLE_OPERATOR",
            "ROLE_API",
        )
        assertThat(IncentiveResource::class.java.getAnnotation(RolesAllowed::class.java).value)
            .containsExactly("ROLE_OPERATOR")
        methods.filter { it.name.substringBefore("$") != "getOffer" }.forEach { m ->
            m.getAnnotation(RolesAllowed::class.java)?.let {
                assertThat(it.value).describedAs(m.name).doesNotContain("ROLE_API")
            }
        }
    }

    @Test
    fun `the named caller's own config mints from the client this set names`() {
        assertThat(OFFER_READ_CALLERS).containsExactly("service-account-openbank-campaign")
        val doc = Yaml().load<Map<String, Any>>(
            File("../openbank-campaign-service/src/main/resources/application.yaml").readText(),
        )
        val oidcClient = (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
        val m2m = oidcClient["m2m"] as Map<*, *>
        assertThat("service-account-${m2m["client-id"]}").isEqualTo("service-account-openbank-campaign")
    }

    @Test
    fun `an allowed principal's name on a token issued to another client is refused`() {
        val impostor = jwtIdentity("openbank-admin-ui", "service-account-openbank-campaign", "ROLE_API")
        assertThatThrownBy { requireNamedOfferReader(impostor) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a human token issued through an allowed client, or a non-JWT principal so named, is refused`() {
        val human = jwtIdentity("openbank-campaign", "alice", "ROLE_API")
        assertThatThrownBy { requireNamedOfferReader(human) }.isInstanceOf(ForbiddenException::class.java)
        val named = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("service-account-openbank-campaign"))
            .addRole("ROLE_API")
            .build()
        assertThatThrownBy { requireNamedOfferReader(named) }.isInstanceOf(ForbiddenException::class.java)
        val noSubject = jwtIdentity("openbank-campaign", "service-account-openbank-campaign", "ROLE_API", sub = null)
        assertThatThrownBy { requireNamedOfferReader(noSubject) }.isInstanceOf(ForbiddenException::class.java)
    }
}
