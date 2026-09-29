// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 batch 3: kyb-service creates the entity party and grants its representation mandates
 * (`party.create`, `party.mandate.grant`) as its OWN principal, `service-account-openbank-kyb`
 * (ROLE_API only), not the shared `openbank-services` one. Two artefacts must agree and nothing at
 * runtime says so when they do not: the party rest-client must select the NAMED oidc-client `m2m`,
 * and `quarkus.oidc-client.m2m` must exist with client `openbank-kyb` and its own secret variable.
 */
class M2mOidcClientIdentityWiringTest {

    private val oidcClient: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `the party rest-client selects the named m2m oidc-client and not the default one`() {
        val client = PartyServiceRestClient::class.java
        val named = client.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).describedAs("@OidcClientFilter on PartyServiceRestClient").isNotNull
        assertThat(named.value).isEqualTo(M2M)
        assertThat(client.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java })
            .describedAs("the default-client filter would re-attach the shared principal's token")
            .doesNotContain(OidcClientRequestReactiveFilter::class.java)
    }

    @Test
    fun `the named m2m oidc-client authenticates as openbank-kyb with its own secret`() {
        val m2m = oidcClient[M2M] as Map<*, *>
        assertThat(m2m["client-id"]).isEqualTo("openbank-kyb")
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
