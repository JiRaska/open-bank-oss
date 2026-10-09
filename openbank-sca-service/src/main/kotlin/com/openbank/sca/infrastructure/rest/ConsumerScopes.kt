// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.sca.domain.model.ConsumerScope
import com.openbank.sca.domain.model.ReservedNamespace

/**
 * Maps an authenticated consumer to its [ConsumerScope] (ADR-0335 D1). The principal id is the
 * Keycloak client_credentials identity `service-account-<clientId>` (rules.yaml: authz_policy).
 * Adding a scoped consumer is a reviewed code change here, pinned by ConsumerScopesTest, and is
 * paired with that identity's identity-scoped rule in sca_rest_ext.rego.
 */
object ConsumerScopes {
    private val reserved: Map<String, ReservedNamespace> = mapOf(
        "service-account-openbank-pension" to ReservedNamespace.PENSION,
    )

    fun forPrincipal(principalId: String?): ConsumerScope =
        reserved[principalId]?.let { ConsumerScope.Reserved(it) } ?: ConsumerScope.General
}
