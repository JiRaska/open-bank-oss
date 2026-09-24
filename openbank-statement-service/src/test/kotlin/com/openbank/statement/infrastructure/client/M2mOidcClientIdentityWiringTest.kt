// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 batch 7: statement-service searches booked entries (`GET /api/v1/transactions/search`,
 * `transaction.search`) as its OWN principal, `service-account-openbank-statement` (ROLE_API only).
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-statement` and its own secret variable (a
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
    fun `the migrated rest-clients select the named oidc-client and not the default one`() {
        listOf(
            TransactionRestClient::class.java,
        ).forEach { client ->
            val named = client.getAnnotation(OidcClientFilter::class.java)
            assertThat(named).describedAs("@OidcClientFilter on %s", client.simpleName).isNotNull
            assertThat(named.value).describedAs("%s oidc-client name", client.simpleName).isEqualTo(NAMED)
            assertThat(client.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java })
                .describedAs(
                    "%s: the default-client filter would re-attach the shared principal's token",
                    client.simpleName,
                )
                .doesNotContain(OidcClientRequestReactiveFilter::class.java)
        }
    }

    @Test
    fun `the named oidc-client authenticates as openbank-statement with its own secret`() {
        val named = oidcClient[NAMED] as Map<*, *>
        assertThat(named["client-id"]).isEqualTo("openbank-statement")
        val secret = (named["credentials"] as Map<*, *>)["secret"] as String
        assertThat(secret).startsWith("\${OIDC_M2M_CLIENT_SECRET:")
        assertThat((named["grant"] as Map<*, *>)["type"]).isEqualTo("client")
    }

    @Test
    fun `the default client stays the shared one until the remaining edges migrate`() {
        assertThat(oidcClient["client-id"]).isEqualTo("openbank-services")
    }

    private companion object {
        const val NAMED = "m2m"
    }
}
