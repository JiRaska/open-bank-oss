// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/** Sets the routing host only when the catalog URL uses the KEDA HTTP interceptor. */
@ApplicationScoped
class ProductCatalogHostHeaderFilter : ClientRequestFilter {
    @ConfigProperty(name = "product-catalog.host-override")
    lateinit var hostOverride: Optional<String>

    override fun filter(requestContext: ClientRequestContext) {
        hostOverride.ifPresent { requestContext.headers.putSingle(HttpHeaders.HOST, it) }
    }
}
