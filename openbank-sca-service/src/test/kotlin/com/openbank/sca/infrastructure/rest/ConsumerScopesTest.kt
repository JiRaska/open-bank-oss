// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.sca.domain.model.ConsumerScope
import com.openbank.sca.domain.model.ReservedNamespace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0335 D1/D4: exactly one identity holds the pension reservation; every other caller is General. */
class ConsumerScopesTest {
    @Test
    fun `the pension client is scoped to the pension namespace`() {
        assertThat(ConsumerScopes.forPrincipal("service-account-openbank-pension"))
            .isEqualTo(ConsumerScope.Reserved(ReservedNamespace.PENSION))
    }

    @Test
    fun `every other caller is a general consumer`() {
        listOf(
            "service-account-openbank-services",
            "service-account-openbank-edge",
            "service-account-openbank-pension-x",
            "openbank-pension",
            "00000000-0000-0000-0000-000000000099",
            null,
        ).forEach { assertThat(ConsumerScopes.forPrincipal(it)).`as`(it.toString()).isEqualTo(ConsumerScope.General) }
    }
}
