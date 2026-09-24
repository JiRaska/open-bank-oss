// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 batch 2 (+ batch 3: the AML case open): this service's writes are made as its OWN principal,
 * `service-account-openbank-sepa-instant` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-sepa-instant` and its own secret variable (a missing named client fails the first call, not
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
    fun `the money-path rest-clients select the named m2m oidc-client and not the default one`() {
        listOf(TransactionServiceClient::class.java, AmlServiceClient::class.java).forEach { client ->
            val named = client.getAnnotation(OidcClientFilter::class.java)
            assertThat(named).describedAs("@OidcClientFilter on %s", client.simpleName).isNotNull
            assertThat(named.value).describedAs("%s oidc-client name", client.simpleName).isEqualTo(M2M)
            assertThat(providers(client))
                .describedAs(
                    "%s: the default-client filter would re-attach the shared principal's token",
                    client.simpleName,
                )
                .doesNotContain(OidcClientRequestReactiveFilter::class.java)
        }
    }

    @Test
    fun `the not-yet-migrated rest-clients keep the default (shared) client`() {
        listOf(ClearingSimulatorClient::class.java).forEach { client ->
            assertThat(providers(client))
                .describedAs("%s still uses the default (shared) client", client.simpleName)
                .contains(OidcClientRequestReactiveFilter::class.java)
            assertThat(client.getAnnotation(OidcClientFilter::class.java)).isNull()
        }
    }

    private fun providers(type: Class<*>): List<Class<*>> =
        type.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java }

    @Test
    fun `the named m2m oidc-client authenticates as openbank-sepa-instant with its own secret`() {
        val m2m = oidcClient[M2M] as Map<*, *>
        assertThat(m2m["client-id"]).isEqualTo("openbank-sepa-instant")
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
