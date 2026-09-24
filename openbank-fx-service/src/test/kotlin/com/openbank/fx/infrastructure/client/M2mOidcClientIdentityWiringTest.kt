// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 batch 3: fx-service opens AML cases (`POST /api/v1/aml/cases`, `amlCase.create`) as its OWN
 * principal, `service-account-openbank-fx` (ROLE_API only), not the shared `openbank-services` one.
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and aml-service's
 * named-caller check refuses it once the shared client loses ROLE_OPERATOR), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-fx` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest {

    private val oidcClient: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `the AML case open selects the named m2m oidc-client and not the default one`() {
        val named = AmlServiceClient::class.java.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).describedAs("@OidcClientFilter on AmlServiceClient").isNotNull
        assertThat(named.value).isEqualTo(M2M)
        val providers = AmlServiceClient::class.java.getAnnotationsByType(RegisterProvider::class.java)
        assertThat(providers.map { it.value.java })
            .describedAs("the default-client filter would re-attach the shared principal's token")
            .doesNotContain(OidcClientRequestReactiveFilter::class.java)
    }

    @Test
    fun `the named m2m oidc-client authenticates as openbank-fx with its own secret`() {
        val m2m = oidcClient[M2M] as Map<*, *>
        assertThat(m2m["client-id"]).isEqualTo("openbank-fx")
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
