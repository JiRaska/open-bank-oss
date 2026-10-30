// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxScopeRoleMapperTest {
    private val mapper = FxScopeRoleMapper("fx:read")

    @Test
    fun `maps space separated scope strings without provider roles`() {
        assertThat(mapper.roles("openid fx:read")).containsExactly(FxRoles.READ)
        assertThat(mapper.roles("fx:read other:scope")).containsExactly(FxRoles.READ)
        assertThat(mapper.roles("")).isEmpty()
    }

    @Test
    fun `maps scope arrays without provider roles`() {
        assertThat(mapper.roles(listOf("fx:read"))).containsExactly(FxRoles.READ)
        assertThat(mapper.roles(listOf("unrelated"))).isEmpty()
    }

    @Test
    fun `ignores unrelated claims and non string collections`() {
        assertThat(mapper.roles(null)).isEmpty()
        assertThat(mapper.roles(42)).isEmpty()
        assertThat(mapper.roles(listOf(1, 2))).isEmpty()
    }
}
