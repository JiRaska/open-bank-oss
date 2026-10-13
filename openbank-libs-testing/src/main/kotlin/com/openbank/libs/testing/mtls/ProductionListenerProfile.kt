// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.mtls

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Boots a service with the listener and HTTP-auth shape its `%prod` profile bakes into the image
 * (#12511). The keys are READ from the service's own `src/main/resources/application.yaml`, through
 * the same SmallRye YAML source Quarkus uses ([ProductionListenerKeys]), and never restated in the test, so deleting the
 * production fix makes [ListenerAuthParityConformance] fail. Copied keys:
 *
 *  - `quarkus.http.ssl.client-auth` (build time, which is why a profile is needed: it rebuilds the app);
 *  - `quarkus.http.insecure-requests` (keeps the plain-HTTP port next to the mTLS one);
 *  - every `quarkus.http.auth.*` key (the bearer-only permission that is the fix).
 *
 * It also turns the OIDC bearer mechanism on (most services switch it off in `%test`); that is
 * build time too, so it cannot come from [ListenerMaterial], which supplies the verification key.
 *
 * Everything else stays the service's `%test` configuration. Subclasses add their own overrides
 * through [extraOverrides] and resources through [extraResources].
 */
abstract class ProductionListenerProfile : QuarkusTestProfile {
    // Each key is also set under `%test.`: SmallRye resolves a profile-prefixed key in ANY source
    // before the bare key, so a service's own `%test.quarkus.oidc.enabled: false` would otherwise
    // beat this override whatever its ordinal.
    override fun getConfigOverrides(): Map<String, String> = (
        ProductionListenerKeys.read() + BEARER_MECHANISM +
            extraOverrides()
        )
        .flatMap { (k, v) -> listOf(k to v, "%test.$k" to v) }
        .toMap()

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(QuarkusTestProfile.TestResourceEntry(ListenerMaterial::class.java)) + extraResources()

    open fun extraOverrides(): Map<String, String> = emptyMap()

    open fun extraResources(): List<QuarkusTestProfile.TestResourceEntry> = emptyList()

    private companion object {
        val BEARER_MECHANISM = mapOf(
            "quarkus.oidc.enabled" to "true",
            "quarkus.oidc.tenant-enabled" to "true",
            "quarkus.oidc.devservices.enabled" to "false",
            "quarkus.keycloak.devservices.enabled" to "false",
        )
    }
}
