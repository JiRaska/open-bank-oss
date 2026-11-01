// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogScopeRoleMapperTest {
    private val mapper = CatalogScopeRoleMapper(
        "catalog:read",
        "catalog:author",
        "catalog:publish",
        approvalConfig(
            "LEGAL_APPROVER" to ("legal:approve" to "ROLE_LEGAL_COUNSEL"),
            "PRODUCT_APPROVER" to ("product:approve" to "ROLE_PRODUCT_OWNER"),
        ),
    )

    @Test
    fun `maps standards based scope strings and arrays without provider roles`() {
        assertThat(mapper.roles("openid catalog:read catalog:author"))
            .containsExactlyInAnyOrder(CatalogRoles.READ, CatalogRoles.AUTHOR)
        assertThat(mapper.roles(listOf("catalog:read", "catalog:publish")))
            .containsExactlyInAnyOrder(CatalogRoles.READ, CatalogRoles.PUBLISH)
        assertThat(mapper.roles("unrelated")).isEmpty()
    }

    @Test
    fun `an approval authority needs its configured scope AND realm role on the same token`() {
        assertThat(mapper.roles("legal:approve product:approve")).isEmpty()
        assertThat(mapper.roles("legal:approve", setOf("ROLE_LEGAL_COUNSEL"))).containsExactly("LEGAL_APPROVER")
        assertThat(mapper.roles("product:approve", setOf("ROLE_PRODUCT_OWNER"))).containsExactly("PRODUCT_APPROVER")
        assertThat(mapper.roles("legal:approve", setOf("ROLE_PRODUCT_OWNER"))).isEmpty()
        assertThat(mapper.approvalAuthorities).containsExactlyInAnyOrder("LEGAL_APPROVER", "PRODUCT_APPROVER")
    }

    @Test
    fun `an authority no deployment binds can never be held`() {
        val unbound = CatalogScopeRoleMapper("catalog:read", "catalog:author", "catalog:publish", approvalConfig())
        assertThat(unbound.roles("legal:approve", setOf("ROLE_LEGAL_COUNSEL"))).isEmpty()
        assertThat(unbound.approvalAuthorities).isEmpty()
    }

    private fun approvalConfig(vararg bindings: Pair<String, Pair<String, String>>) =
        object : CatalogApprovalSecurityConfig {
            override fun authorities() = bindings.associate { (authority, binding) ->
                authority to object : CatalogApprovalSecurityConfig.AuthorityBinding {
                    override fun scope() = binding.first
                    override fun realmRole() = binding.second
                }
            }

            override fun evidenceReaders(): Map<String, String> = emptyMap()
        }
}
