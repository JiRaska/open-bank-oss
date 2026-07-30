// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Routes product-catalog reads through the KEDA HTTP interceptor (ADR-0083 T1 re-entry).
 *
 * Same mechanism as account-service's identically-named filter: the interceptor dispatches on
 * the `Host` header (`product-catalog.accounts.svc` in its HTTPScaledObject), the connection
 * URL points at the shared proxy, so `Host` must be set explicitly from
 * `product-catalog-api.host-override`. Unset by default — local dev dials `localhost:8104`
 * directly and this filter stays inert. This service's onboarding-document resolution
 * (ADR-0162 D7) is the caller whose silent degradation (no template -> no document -> no
 * signature ceremony) took onboarding down the last time product-catalog was at min:0.
 */
@ApplicationScoped
class ProductCatalogHostHeaderFilter : ClientRequestFilter {

    @ConfigProperty(name = "product-catalog-api.host-override")
    lateinit var hostOverride: Optional<String>

    override fun filter(requestContext: ClientRequestContext) {
        hostOverride.ifPresent { requestContext.headers.putSingle(HttpHeaders.HOST, it) }
    }
}
