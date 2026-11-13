// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision.shadow

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProductShadowRecorderTest {

    private val registry = SimpleMeterRegistry()
    private val recorder = ProductShadowRecorder(registry, "billing")

    private fun count(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    @Test
    fun `diverged is a subset of compared and agreement never touches diverged`() {
        recorder.compared("CURRENT-ACCOUNT", "fee.maintenance", diverged = false)
        recorder.compared("CURRENT-ACCOUNT", "fee.maintenance", diverged = true)
        val tags = arrayOf("consumer", "billing", "product", "CURRENT-ACCOUNT", "rule_id", "fee.maintenance")
        assertThat(count(ProductShadowRecorder.COMPARED, *tags)).isEqualTo(2.0)
        assertThat(count(ProductShadowRecorder.DIVERGED, *tags)).isEqualTo(1.0)
    }

    @Test
    fun `each not-comparable reason is its own series and never counts as compared`() {
        recorder.inputQueued()
        recorder.notComparable(ShadowNotComparable.NO_IR)
        recorder.notComparable(ShadowNotComparable.VERIFICATION_FAILED)
        assertThat(count(ProductShadowRecorder.INPUTS, "consumer", "billing")).isEqualTo(1.0)
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "no_ir")).isEqualTo(1.0)
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "verification_failed")).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counters()).isEmpty()
    }

    @Test
    fun `no registry is a no-op and a malformed consumer name is refused`() {
        val silent = ProductShadowRecorder(null, "interest")
        silent.inputQueued()
        silent.compared("P", "r", diverged = true)
        silent.notComparable(ShadowNotComparable.NO_IR)
        assertThatThrownBy { ProductShadowRecorder(registry, "Billing Service") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
