// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import io.smallrye.config.SmallRyeConfigBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PaymentWorkerVersioningConfigTest {
    @Test
    fun `missing versioning configuration refuses unsafe unversioned polling`() {
        val config = SmallRyeConfigBuilder().withMapping(PaymentWorkerVersioningConfig::class.java)
            .build().getConfigMapping(PaymentWorkerVersioningConfig::class.java)
        assertThat(config.enabled()).isFalse()
        assertThatThrownBy { paymentWorkerOptions(config) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("require versioning")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "latest", "v1.0.0", "abcdef1"])
    fun `enabled versioning rejects missing or mutable build identity`(build: String) {
        val config = config(build)
        assertThatThrownBy { paymentWorkerOptions(config) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `full immutable identity enables worker deployment polling`() {
        val options = paymentWorkerOptions(config(BUILD)).deploymentOptions
        assertThat(options.isUsingVersioning).isTrue()
        assertThat(options.version.deploymentName).isEqualTo("payment-execution")
        assertThat(options.version.buildId).isEqualTo(BUILD)
    }

    private fun config(build: String): PaymentWorkerVersioningConfig = SmallRyeConfigBuilder()
        .withMapping(PaymentWorkerVersioningConfig::class.java)
        .withDefaultValue("openbank.transaction.worker.versioning.enabled", "true")
        .withDefaultValue("openbank.transaction.worker.versioning.deployment-name", "payment-execution")
        .withDefaultValue("openbank.transaction.worker.versioning.build-id", build)
        .build().getConfigMapping(PaymentWorkerVersioningConfig::class.java)

    private companion object {
        const val BUILD = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
