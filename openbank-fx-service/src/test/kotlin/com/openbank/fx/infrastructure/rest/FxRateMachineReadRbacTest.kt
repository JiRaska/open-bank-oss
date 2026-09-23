// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10486 batch 4: ROLE_API reaches the two published-rate reads and nothing else in FxResource —
 * a conversion record or a convert stays closed to a machine caller at the RBAC gate.
 */
class FxRateMachineReadRbacTest {

    private fun roles(name: String) = FxResource::class.java.declaredMethods
        .single { it.name == name }
        .getAnnotation(RolesAllowed::class.java).value.toList()

    @Test
    fun `the published rate reads admit ROLE_API`() {
        assertThat(roles("getRates")).contains("ROLE_API")
        assertThat(roles("getRate")).contains("ROLE_API")
    }

    @Test
    fun `conversion reads and the convert write do not admit ROLE_API`() {
        assertThat(roles("getConversion")).doesNotContain("ROLE_API")
        assertThat(roles("convert")).doesNotContain("ROLE_API")
    }
}
