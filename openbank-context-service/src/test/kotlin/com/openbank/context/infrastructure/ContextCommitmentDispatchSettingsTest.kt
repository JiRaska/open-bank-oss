// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ContextCommitmentDispatchSettingsTest {
    @Test
    fun `bounded claim accepts its configured endpoints`() {
        assertThat(ContextCommitmentDispatchSettings(1, "synthetic-bank").batchSize).isEqualTo(1)
        assertThat(ContextCommitmentDispatchSettings(1000, "synthetic-bank").batchSize).isEqualTo(1000)
    }

    @Test
    fun `invalid claim size fails before dispatch`() {
        assertThatIllegalArgumentException().isThrownBy { ContextCommitmentDispatchSettings(0, "synthetic-bank") }
        assertThatIllegalArgumentException().isThrownBy { ContextCommitmentDispatchSettings(1001, "synthetic-bank") }
    }
}
