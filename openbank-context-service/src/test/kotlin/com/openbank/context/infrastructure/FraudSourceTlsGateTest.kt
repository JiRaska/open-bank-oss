// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FraudSourceTlsGateTest {
    @Test
    fun `requires HTTPS without embedded credentials or query`() {
        assertThat(secureFraudSourceUrl("https://fraud.example:8443")).isTrue()
        assertThat(secureFraudSourceUrl("http://fraud.example:8443")).isFalse()
        assertThat(secureFraudSourceUrl("https://user:secret@fraud.example:8443")).isFalse()
        assertThat(secureFraudSourceUrl("https://fraud.example:8443/?token=secret")).isFalse()
        assertThat(secureFraudSourceUrl("nonsense")).isFalse()
    }
}
