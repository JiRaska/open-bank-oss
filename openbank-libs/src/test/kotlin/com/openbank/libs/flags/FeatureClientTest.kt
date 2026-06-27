// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeatureClientTest {

    @Test
    fun `static client resolves a known boolean override as STATIC`() {
        val flags = StaticFeatureClient(mapOf("new-router" to true))

        val eval = flags.boolean("new-router", default = false)

        assertThat(eval.value).isTrue()
        assertThat(eval.variant).isEqualTo("static")
        assertThat(eval.reason).isEqualTo(EvaluationReason.STATIC)
        assertThat(eval.resolved).isTrue()
    }

    @Test
    fun `static client falls through to default for an unknown flag`() {
        val flags = StaticFeatureClient()

        val eval = flags.boolean("absent", default = true)

        assertThat(eval.value).isTrue()
        assertThat(eval.reason).isEqualTo(EvaluationReason.DEFAULT)
        assertThat(eval.resolved).isFalse()
    }

    @Test
    fun `static client treats a type mismatch as not-set`() {
        // override is a String; asking for a boolean must not coerce — yields default.
        val flags = StaticFeatureClient(mapOf("misc" to "on"))

        assertThat(flags.boolean("misc", default = false).reason).isEqualTo(EvaluationReason.DEFAULT)
        assertThat(flags.string("misc", default = "x").value).isEqualTo("on")
    }

    @Test
    fun `static client supports string integer and double overrides`() {
        val flags = StaticFeatureClient(mapOf("name" to "blue", "count" to 7L, "ratio" to 0.25))

        assertThat(flags.string("name", "red").value).isEqualTo("blue")
        assertThat(flags.integer("count", 0).value).isEqualTo(7L)
        assertThat(flags.double("ratio", 0.0).value).isEqualTo(0.25)
    }

    @Test
    fun `enabled convenience returns the resolved boolean`() {
        val flags = StaticFeatureClient(mapOf("on" to true, "off" to false))

        assertThat(flags.enabled("on")).isTrue()
        assertThat(flags.enabled("off")).isFalse()
        assertThat(flags.enabled("absent")).isFalse()
    }

    @Test
    fun `defaults client returns every default with DEFAULT reason`() {
        val flags = DefaultsFeatureClient()

        assertThat(flags.boolean("a", default = true).value).isTrue()
        assertThat(flags.boolean("a", default = true).reason).isEqualTo(EvaluationReason.DEFAULT)
        assertThat(flags.string("b", "z").value).isEqualTo("z")
        assertThat(flags.integer("c", 9).value).isEqualTo(9L)
        assertThat(flags.double("d", 1.5).value).isEqualTo(1.5)
        assertThat(flags.enabled("e")).isFalse()
    }

    @Test
    fun `resolved is true only for provider-decided reasons`() {
        fun ev(r: EvaluationReason) = FlagEvaluation("f", true, reason = r)

        assertThat(ev(EvaluationReason.STATIC).resolved).isTrue()
        assertThat(ev(EvaluationReason.TARGETING_MATCH).resolved).isTrue()
        assertThat(ev(EvaluationReason.SPLIT).resolved).isTrue()
        assertThat(ev(EvaluationReason.DISABLED).resolved).isFalse()
        assertThat(ev(EvaluationReason.DEFAULT).resolved).isFalse()
        assertThat(ev(EvaluationReason.ERROR).resolved).isFalse()
        assertThat(ev(EvaluationReason.UNKNOWN).resolved).isFalse()
    }

    @Test
    fun `empty eval context is the shared singleton`() {
        assertThat(EvalContext.EMPTY.targetingKey).isNull()
        assertThat(EvalContext.EMPTY.attributes).isEmpty()
    }
}
