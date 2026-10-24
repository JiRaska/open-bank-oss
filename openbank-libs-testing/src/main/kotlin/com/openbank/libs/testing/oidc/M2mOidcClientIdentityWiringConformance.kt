// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.oidc

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * Conformance suite for a service that makes its outbound calls as its OWN principal
 * (`service-account-openbank-<service>`, ROLE_API only) instead of the shared `openbank-services`
 * one (#10486, #13169). Replaces the per-service `M2mOidcClientIdentityWiringTest` copies: a
 * service names its parameters in a subclass and inherits every check below.
 *
 * Two artefacts must agree and nothing at runtime says so when they do not: each migrated
 * rest-client must select the NAMED oidc-client (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.<name>` must exist with the
 * service's own client id and its own secret variable (a missing named client fails the first
 * call, not the build).
 *
 * A subclass that deletes its parameter FAILS rather than passes vacuously: [namedClients] must be
 * non-empty and [clientId] must equal the value in `application.yaml`.
 */
@Suppress("FunctionNaming") // one backticked @Test per case, as ApprovalEndpointConformance
abstract class M2mOidcClientIdentityWiringConformance {

    /** `quarkus.oidc-client.<this>` and the `@OidcClientFilter` value the clients select. */
    protected open val oidcClientName: String = "m2m"

    /** The Keycloak client id this service authenticates as, e.g. `openbank-account`. */
    protected abstract val clientId: String

    /** Every rest-client that must select the named oidc-client and not register the default filter. */
    protected abstract val namedClients: List<Class<*>>

    /** `quarkus.oidc-client.client-id` the default client still carries. */
    protected open val defaultClientId: String = "openbank-services"

    /** Classpath resource holding `quarkus.oidc-client`; overridden only by the kit's own self-test. */
    protected open val configResource: String = "application.yaml"

    private val oidcClient: Map<*, *> by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(configResource)) {
            "$configResource is not on the test classpath"
        }
        val doc = stream.use { Yaml().load<Map<String, Any>>(it) }
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    /** The named client's config block, for a subclass that pins extra keys of its own. */
    protected fun namedClientConfig(): Map<*, *> = checkNotNull(oidcClient[oidcClientName] as? Map<*, *>) {
        "quarkus.oidc-client.$oidcClientName is missing from application.yaml"
    }

    /** Extra keys a service pins on its named client block (copilot: the machine realm URL). */
    protected open fun assertNamedClientExtras(named: Map<*, *>) = Unit

    @Test
    fun `the migrated rest-clients select the named oidc-client and not the default one`() {
        assertThat(namedClients).describedAs("a conformance subclass must name at least one client").isNotEmpty
        namedClients.forEach { client ->
            val named = client.getAnnotation(OidcClientFilter::class.java)
            assertThat(named).describedAs("@OidcClientFilter on %s", client.simpleName).isNotNull
            assertThat(named.value).describedAs("%s oidc-client name", client.simpleName).isEqualTo(oidcClientName)
            assertThat(client.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java })
                .describedAs(
                    "%s: the default-client filter would re-attach the shared principal's token",
                    client.simpleName,
                )
                .doesNotContain(OidcClientRequestReactiveFilter::class.java)
        }
    }

    @Test
    fun `the named oidc-client authenticates as its own client with its own secret`() {
        val named = namedClientConfig()
        assertThat(named["client-id"]).isEqualTo(clientId)
        val secret = (named["credentials"] as Map<*, *>)["secret"] as String
        assertThat(secret).startsWith("\${OIDC_M2M_CLIENT_SECRET:")
        assertThat((named["grant"] as Map<*, *>)["type"]).isEqualTo("client")
        assertNamedClientExtras(named)
    }

    @Test
    fun `the default client stays the shared one until the remaining edges migrate`() {
        assertThat(oidcClient["client-id"]).isEqualTo(defaultClientId)
    }
}
