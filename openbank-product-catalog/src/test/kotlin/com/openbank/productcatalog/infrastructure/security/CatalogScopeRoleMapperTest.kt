// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogScopeRoleMapperTest {
    private val mapper = CatalogScopeRoleMapper("catalog:read", "catalog:author", "catalog:publish")

    @Test
    fun `maps standards based scope strings and arrays without provider roles`() {
        assertThat(mapper.roles("openid catalog:read catalog:author"))
            .containsExactlyInAnyOrder(CatalogRoles.READ, CatalogRoles.AUTHOR)
        assertThat(mapper.roles(listOf("catalog:read", "catalog:publish")))
            .containsExactlyInAnyOrder(CatalogRoles.READ, CatalogRoles.PUBLISH)
        assertThat(mapper.roles("unrelated")).isEmpty()
    }
}
