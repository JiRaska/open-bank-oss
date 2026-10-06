// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextSourceTlsGateTest {
    @Test
    fun `requires HTTPS without embedded credentials or query`() {
        assertThat(secureContextSourceUrl("https://context.example:8443")).isTrue()
        assertThat(secureContextSourceUrl("http://context.example:8443")).isFalse()
        assertThat(secureContextSourceUrl("https://user:secret@context.example:8443")).isFalse()
        assertThat(secureContextSourceUrl("https://context.example:8443/?token=secret")).isFalse()
        assertThat(secureContextSourceUrl("nonsense")).isFalse()
    }
}
