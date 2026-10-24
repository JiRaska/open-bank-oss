// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.junit.jupiter.api.Test

/**
 * ADR-0319 moved [TransactionServiceClient] from a hand-written interface to a sub-interface of the
 * generated one. The outbound identity must not have moved with it: this client authenticates as the
 * DEFAULT oidc-client (card-processing has no named `m2m` client), which `@OidcClientFilter` with no
 * value selects - exactly as on `main`.
 *
 * Two ways to get that wrong and neither fails at runtime: naming a client that does not exist in
 * `application.yaml` (the first call fails, not the build), or ALSO registering
 * [OidcClientRequestReactiveFilter] as a provider next to the annotation, which attaches the bearer
 * twice. The generated parent carries neither annotation, so what the sub-interface declares is the
 * whole truth.
 */
class M2mOidcClientIdentityWiringTest {

    @Test
    fun `the transaction client keeps the bare default oidc-client filter, as on main`() {
        val filter = TransactionServiceClient::class.java.getAnnotation(OidcClientFilter::class.java)

        assertThat(filter).describedAs("@OidcClientFilter on TransactionServiceClient").isNotNull
        assertThat(filter.value).describedAs("a bare filter selects the default oidc-client").isEmpty()
    }

    @Test
    fun `the oidc request filter is not registered a second time as a provider`() {
        val providers = TransactionServiceClient::class.java
            .getAnnotationsByType(RegisterProvider::class.java)
            .map { it.value.java }

        assertThat(providers).doesNotContain(OidcClientRequestReactiveFilter::class.java)
        assertThat(providers).containsExactly(SyntheticTaintClientFilter::class.java)
    }

    @Test
    fun `the config key and the route come from this consumer, not the generated parent`() {
        val rest = TransactionServiceClient::class.java.getAnnotation(RegisterRestClient::class.java)

        assertThat(rest.configKey).isEqualTo("transaction-api")
    }
}
