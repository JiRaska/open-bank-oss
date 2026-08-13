// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class StandaloneIssuerGuardTest {
    @Test
    fun `requires https whenever the standalone guard is enabled`() {
        assertThatCode { StandaloneIssuerGuard(true, "https://issuer.example", false, Optional.empty()) }
            .doesNotThrowAnyException()
        assertThatThrownBy { StandaloneIssuerGuard(true, "http://issuer.example", false, Optional.empty()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must use https")
        assertThatCode { StandaloneIssuerGuard(false, "http://localhost:8080", true, Optional.of("banking")) }
            .doesNotThrowAnyException()
        assertThatThrownBy {
            StandaloneIssuerGuard(true, "https://issuer.example", true, Optional.of("insurance"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("banking pack")
    }
}
