// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import io.quarkus.oidc.client.NamedOidcClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 batch 2: this service's money-path writes are made as its OWN principal,
 * `service-account-openbank-domestic-payment` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-domestic-payment` and its own secret variable (a missing named client fails the first call, not
 * the build).
 */
class M2mOidcClientIdentityWiringTest {

    private val oidcClient: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `the settlement adapter injects the named m2m oidc-client`() {
        val qualifiers = SettlementAdapter::class.java.declaredConstructors.single().parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<NamedOidcClient>()
        assertThat(qualifiers.map { it.value })
            .describedAs("SettlementAdapter must mint its bearer from the named client, not the shared default")
            .containsExactly(M2M)
    }

    @Test
    fun `the account lookup stays on the shared default client`() {
        val qualifiers = AccountServiceClient::class.java.declaredConstructors.flatMap { c ->
            c.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<NamedOidcClient>()
        }
        assertThat(qualifiers).describedAs("AccountServiceClient is a read edge, not migrated in batch 2").isEmpty()
    }

    @Test
    fun `the named m2m oidc-client authenticates as openbank-domestic-payment with its own secret`() {
        val m2m = oidcClient[M2M] as Map<*, *>
        assertThat(m2m["client-id"]).isEqualTo("openbank-domestic-payment")
        val secret = (m2m["credentials"] as Map<*, *>)["secret"] as String
        assertThat(secret).startsWith("\${OIDC_M2M_CLIENT_SECRET:")
        assertThat(((m2m["grant"] as Map<*, *>)["type"])).isEqualTo("client")
    }

    @Test
    fun `the default client stays the shared one until the remaining edges migrate`() {
        assertThat(oidcClient["client-id"]).isEqualTo("openbank-services")
    }

    private companion object {
        const val M2M = "m2m"
    }
}
