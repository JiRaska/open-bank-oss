// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Routes product-catalog reads through the KEDA HTTP interceptor (ADR-0083 T1 re-entry).
 *
 * The interceptor dispatches on the `Host` header — its HTTPScaledObject lists
 * `product-catalog.accounts.svc` — but the client's connection URL must point at the shared
 * proxy (`keda-add-ons-http-interceptor-proxy.keda:8080`), so the URL's own host can never be
 * the one the interceptor matches. This filter sets `Host` explicitly from
 * `product-catalog.host-override`. It is unset by default, so local dev (direct
 * `localhost:8104`) is unaffected: the filter only engages where gitops sets the override.
 *
 * This is the precondition the reverted T1 declared in `rules.yaml: finops_tiers.declared`
 * demands before product-catalog may leave min:1 — every in-cluster caller must reach the
 * service through the interceptor, so a scaled-to-zero pod wakes instead of answering
 * `Connection refused` on the onboarding path (#668 account-open validation here,
 * ADR-0162 D7 template resolution in document-service).
 */
@ApplicationScoped
class ProductCatalogHostHeaderFilter : ClientRequestFilter {

    @ConfigProperty(name = "product-catalog.host-override")
    lateinit var hostOverride: Optional<String>

    override fun filter(requestContext: ClientRequestContext) {
        hostOverride.ifPresent { requestContext.headers.putSingle(HttpHeaders.HOST, it) }
    }
}
