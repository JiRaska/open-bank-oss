// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** #12511: production listener settings must protect a future unannotated API route. */
@QuarkusTest
@TestProfile(ProductCatalogListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products_listener_it")],
)
class ProductCatalogUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/product-catalog-listener-auth-probe"
}

/** Test-only route with no class or method security annotation. */
@Path("/api/v1/product-catalog-listener-auth-probe")
class ProductCatalogListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
