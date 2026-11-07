// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.oidc

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test

/**
 * The conformance kit is only worth having if deleting or corrupting a subclass's parameter turns it
 * red. Each case below drives the kit's own checks against a deliberately broken subclass and
 * requires the matching check to throw — the negative case, written first, for a guard that would
 * otherwise print green while checking nothing.
 */
@Suppress("FunctionNaming")
class M2mOidcClientIdentityWiringConformanceTest {

    @OidcClientFilter("m2m")
    private interface NamedClient

    @OidcClientFilter
    private interface BareDefaultClient

    @OidcClientFilter("m2m")
    @RegisterProvider(OidcClientRequestReactiveFilter::class)
    private interface DoubleFilteredClient

    private interface UnannotatedClient

    private fun subject(
        id: String = "openbank-fixture",
        clients: List<Class<*>> = listOf(NamedClient::class.java),
        default: String = "openbank-services",
        name: String = "m2m",
    ) = object : M2mOidcClientIdentityWiringConformance(name, "m2m-conformance-fixture.yaml", default) {
        override val clientId = id
        override val namedClients = clients
    }

    @Test
    fun `a service-style subclass is pinned to application-yaml and cannot be pointed elsewhere`() {
        val serviceStyle = object : M2mOidcClientIdentityWiringConformance() {
            override val clientId = "openbank-fixture"
            override val namedClients = listOf<Class<*>>(NamedClient::class.java)
        }
        // libs-testing has no application.yaml: the public constructor reads that name and nothing else.
        assertThatThrownBy {
            serviceStyle.`the named oidc-client authenticates as its own client with its own secret`()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("application.yaml")
    }

    @Test
    fun `a fully conforming subclass passes every check`() {
        val ok = subject()
        ok.`the migrated rest-clients select the named oidc-client and not the default one`()
        ok.`the named oidc-client authenticates as its own client with its own secret`()
        ok.`the default client stays the shared one until the remaining edges migrate`()
    }

    @Test
    fun `an empty client list fails instead of passing vacuously`() {
        assertThatThrownBy {
            subject(clients = emptyList())
                .`the migrated rest-clients select the named oidc-client and not the default one`()
        }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `a client with no filter, a bare default filter or a double filter fails`() {
        listOf(UnannotatedClient::class.java, BareDefaultClient::class.java, DoubleFilteredClient::class.java)
            .forEach { bad ->
                assertThatThrownBy {
                    subject(clients = listOf(bad))
                        .`the migrated rest-clients select the named oidc-client and not the default one`()
                }.describedAs(bad.simpleName).isInstanceOf(AssertionError::class.java)
            }
    }

    @Test
    fun `a wrong client id fails`() {
        assertThatThrownBy {
            subject(id = "openbank-someone-else")
                .`the named oidc-client authenticates as its own client with its own secret`()
        }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `a wrong default client id fails`() {
        assertThatThrownBy {
            subject(default = "openbank-fixture")
                .`the default client stays the shared one until the remaining edges migrate`()
        }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `a named client that does not exist in the yaml fails`() {
        assertThatThrownBy {
            subject(name = "nope").`the named oidc-client authenticates as its own client with its own secret`()
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
