// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.security

import com.openbank.productcatalog.infrastructure.persistence.BankV1PackGuard
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class StandaloneIssuerGuardTest {
    @Test
    fun `requires https whenever the standalone guard is enabled`() {
        assertThatCode { StandaloneIssuerGuard(true, "https://issuer.example") }
            .doesNotThrowAnyException()
        assertThatThrownBy { StandaloneIssuerGuard(true, "http://issuer.example") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must use https")
        assertThatCode { StandaloneIssuerGuard(false, "http://localhost:8080") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a standalone boot with v1 compatibility requires the banking pack`() {
        assertThatCode { BankV1PackGuard(true, true, Optional.of("banking,insurance")) }.doesNotThrowAnyException()
        assertThatCode { BankV1PackGuard(true, false, Optional.of("insurance")) }.doesNotThrowAnyException()
        assertThatCode { BankV1PackGuard(false, true, Optional.empty()) }.doesNotThrowAnyException()
        assertThatThrownBy { BankV1PackGuard(true, true, Optional.of("insurance")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("banking pack")
    }
}
