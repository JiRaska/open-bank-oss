// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #10486 batch 6: `GET /api/v1/cards/{id}` and `GET /api/v1/cards/party/{partyId}` admit ROLE_API so
 * delegation-service and party-service can read as their OWN principals once the shared
 * `openbank-services` client loses ROLE_OPERATOR. ROLE_API is held by EVERY service account and
 * card-issuance runs OPA advisory, so the named-caller check is what refuses the rest.
 */
class CardReadCallerGuardTest {

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
    fun `each named principal with ROLE_API only may make its own read and not the other`() {
        val delegation = identity("service-account-openbank-delegation", "ROLE_API")
        val party = identity("service-account-openbank-party", "ROLE_API")
        assertThatCode { requireNamedCardReader(delegation, CARD_READ_CALLERS) }.doesNotThrowAnyException()
        assertThatCode { requireNamedCardReader(party, CARD_PARTY_LIST_CALLERS) }.doesNotThrowAnyException()
        assertThatThrownBy { requireNamedCardReader(delegation, CARD_PARTY_LIST_CALLERS) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { requireNamedCardReader(party, CARD_READ_CALLERS) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `another service account and the shared client holding ROLE_API are refused`() {
        listOf("service-account-openbank-mcp", "service-account-openbank-services").forEach { name ->
            listOf(CARD_READ_CALLERS, CARD_PARTY_LIST_CALLERS).forEach { allowed ->
                assertThatThrownBy { requireNamedCardReader(identity(name, "ROLE_API"), allowed) }
                    .describedAs(name)
                    .isInstanceOf(ForbiddenException::class.java)
            }
        }
    }

    @Test
    fun `staff roles keep the pre-#10486 behaviour`() {
        listOf("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN").forEach { role ->
            assertThatCode { requireNamedCardReader(identity("u-staff", role), CARD_READ_CALLERS) }
                .describedAs(role)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `only the two machine reads admit ROLE_API`() {
        val methods = CardResource::class.java.declaredMethods
        val withApi = methods.filter { m ->
            m.getAnnotation(RolesAllowed::class.java)?.value?.contains("ROLE_API") == true
        }.map { it.name.substringBefore("$") }.toSet()
        assertThat(withApi).isEqualTo(setOf("getCard", "listByParty"))
    }

    @Test
    fun `the Kotlin caller sets and the rego identity rules list the same principals`() {
        val rego = File("../openbank-infra/gitops/components/payments/card_issuance_rest_ext.rego").readText()
        fun principals(reason: String): Set<String> {
            val rule = rego.substringAfter("allowed_reasons contains \"$reason\"").substringBefore("\n}")
            return Regex("\"(service-account-[a-z0-9-]+)\"").findAll(rule).map { it.groupValues[1] }.toSet()
        }
        assertThat(principals("service-delegation-card-read")).isEqualTo(CARD_READ_CALLERS)
        assertThat(principals("service-party-card-list")).isEqualTo(CARD_PARTY_LIST_CALLERS)
    }

    @Test
    fun `an allowed principal's name on a token issued to another client is refused`() {
        val impostor = jwtIdentity("openbank-admin-ui", "service-account-openbank-delegation", "ROLE_API")
        assertThatThrownBy {
            requireNamedCardReader(impostor, CARD_READ_CALLERS)
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a human token issued through an allowed client, or a non-JWT principal so named, is refused`() {
        val human = jwtIdentity("openbank-delegation", "alice", "ROLE_API")
        assertThatThrownBy {
            requireNamedCardReader(human, CARD_READ_CALLERS)
        }.isInstanceOf(ForbiddenException::class.java)
        val named = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("service-account-openbank-delegation"))
            .addRole("ROLE_API")
            .build()
        assertThatThrownBy {
            requireNamedCardReader(named, CARD_READ_CALLERS)
        }.isInstanceOf(ForbiddenException::class.java)
        val noSubject =
            jwtIdentity("openbank-delegation", "service-account-openbank-delegation", "ROLE_API", sub = null)
        assertThatThrownBy {
            requireNamedCardReader(noSubject, CARD_READ_CALLERS)
        }.isInstanceOf(ForbiddenException::class.java)
    }
}
