// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard: ProductCatalogResource had zero security annotations anywhere — including
 * create/update/activate/deactivate, which mutate the live product/fee catalog. The reads
 * (list/getById/getByCode/getFees) stay intentionally open (public reference data for the admin
 * UI's pricing screens); this test pins that split so a future PR can't silently widen or
 * narrow it without a visible diff here.
 */
class ProductCatalogAuthzTest {

    private val mutatingMethodNames = setOf("create", "update", "activate", "deactivate")

    @Test
    fun `every mutating endpoint is role-gated`() {
        val methods = ProductCatalogResource::class.java.declaredMethods
            .filter { it.name in mutatingMethodNames }
            .filter { it.getAnnotation(POST::class.java) != null || it.getAnnotation(PUT::class.java) != null }

        assertThat(methods).describedAs("expected to find the 4 mutating endpoints by reflection").hasSize(4)
        methods.forEach { m ->
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s must be @RolesAllowed", m.name)
                .isNotNull()
        }
    }

    @Test
    fun `read endpoints stay intentionally open`() {
        listOf("list", "getById", "getByCode", "getFees").forEach { name ->
            val m = ProductCatalogResource::class.java.declaredMethods.single { it.name == name }
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s is public reference data by design — should NOT carry @RolesAllowed", name)
                .isNull()
        }
    }
}
