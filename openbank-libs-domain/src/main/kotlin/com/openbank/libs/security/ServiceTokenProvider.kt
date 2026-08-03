// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

/**
 * Source of bearer tokens for service-to-service calls.
 *
 * The recommended Quarkus production implementation is `quarkus-oidc-client-reactive-filter`:
 * it fetches a token via the OAuth2 Client Credentials grant from Keycloak, caches it until
 * expiry and renews automatically. Applying `@OidcClientFilter` on a `@RegisterRestClient`
 * interface inserts the `Authorization: Bearer …` header per call.
 *
 * This interface exists so libs-level utilities (e.g. test doubles, in-process fakes used
 * for chaos drills) can be swapped in without depending on the Quarkus OIDC client at
 * compile time.
 */
interface ServiceTokenProvider {
    /** Returns a non-empty bearer token suitable for an `Authorization` header. May block. */
    fun getToken(): String

    /** Force an immediate refresh of the cached token, ignoring TTL. */
    fun refresh()
}
