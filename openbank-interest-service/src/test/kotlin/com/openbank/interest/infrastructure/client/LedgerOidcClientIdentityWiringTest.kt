// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * #10486 step 1: the capitalization journal (ledger.create) is posted as interest-service's OWN
 * principal, `service-account-openbank-interest`, not the shared `openbank-services` one. That is
 * two artefacts that must agree and nothing at runtime says so when they do not:
 *
 *  - [LedgerRestClient] must select the NAMED oidc-client (`@OidcClientFilter("ledger")`) and must
 *    NOT also carry the default-client filter, or the bearer token is the shared principal's again
 *    (ledger's OPA rule for interest then never fires and the post rides the shared grant);
 *  - `quarkus.oidc-client.ledger` must exist and name client `openbank-interest` with its own
 *    secret variable. A missing named client does not fail the build; it fails the first post.
 *
 * And the converse, which keeps this step narrow: the other rest-clients (transaction, account,
 * product-catalog) stay on the default client until their own edges are migrated.
 */
class LedgerOidcClientIdentityWiringTest {

    private val oidcClient: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `the ledger client selects the named oidc-client and not the default one`() {
        val named = LedgerRestClient::class.java.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).describedAs("@OidcClientFilter on LedgerRestClient").isNotNull
        assertThat(named.value).isEqualTo(LEDGER_CLIENT)

        assertThat(providers(LedgerRestClient::class.java))
            .describedAs("the default-client filter would re-attach the shared principal's token")
            .doesNotContain(OidcClientRequestReactiveFilter::class.java)
    }

    @Test
    fun `the named ledger oidc-client authenticates as openbank-interest with its own secret`() {
        val ledger = oidcClient[LEDGER_CLIENT] as Map<*, *>
        assertThat(ledger["client-id"]).isEqualTo("openbank-interest")
        assertThat((ledger["grant"] as Map<*, *>)["type"]).isEqualTo("client")

        val secret = (ledger["credentials"] as Map<*, *>)["secret"] as String
        assertThat(secret).startsWith("\${OIDC_LEDGER_CLIENT_SECRET:")
        assertThat(secret).doesNotStartWith("\${OIDC_CLIENT_SECRET:")
    }

    @Test
    fun `the default oidc-client stays the shared one for the not-yet-migrated edges`() {
        assertThat(oidcClient["client-id"]).isEqualTo("openbank-services")
        listOf(TransactionServiceClient::class.java, AccountServiceClient::class.java, ProductCatalogClient::class.java)
            .forEach { client ->
                assertThat(providers(client))
                    .describedAs("%s still uses the default (shared) client", client.simpleName)
                    .contains(OidcClientRequestReactiveFilter::class.java)
                assertThat(client.getAnnotation(OidcClientFilter::class.java)).isNull()
            }
    }

    private fun providers(type: Class<*>): List<Class<*>> =
        type.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java }

    private companion object {
        const val LEDGER_CLIENT = "ledger"
    }
}
