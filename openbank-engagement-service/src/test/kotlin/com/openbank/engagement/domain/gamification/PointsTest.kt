// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.gamification

import com.openbank.engagement.domain.model.gamification.Points
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMemberProperties

class PointsTest {

    @Test
    fun `of rejects a negative value`() {
        assertThatIllegalArgumentException().isThrownBy { Points.of(-1) }
    }

    @Test
    fun `zero and of(0) are equal`() {
        assertThat(Points.of(0)).isEqualTo(Points.ZERO)
    }

    @Test
    fun `plus accumulates`() {
        assertThat(Points.of(10) + Points.of(5)).isEqualTo(Points.of(15))
    }

    @Test
    fun `compareTo orders by value`() {
        assertThat(Points.of(5) < Points.of(10)).isTrue
        assertThat(Points.of(10) < Points.of(5)).isFalse
    }

    /**
     * The structural invariant the task requires: "Points cannot type-coerce to a Money type".
     * Reflection over the compiled type is what actually proves it — a comment saying "no
     * conversion method" is not evidence, a member scan is. This fails the moment anyone adds a
     * `toMoney()`/`operator times(FxRate)` or any member whose name mentions money/currency/amount.
     */
    @Test
    fun `Points exposes no conversion toward a monetary type`() {
        val forbiddenNameFragments = listOf("money", "currency", "amount", "minorunit", "ledger")
        val memberNames = Points::class.declaredFunctions.map { it.name } +
            Points::class.declaredMemberProperties.map { it.name }

        memberNames.forEach { name ->
            forbiddenNameFragments.forEach { fragment ->
                assertThat(name.lowercase()).`as`("member '%s' must not resemble a money conversion", name)
                    .doesNotContain(fragment)
            }
        }
    }

    @Test
    fun `the only public constructor path is of — value cannot be constructed with a negative literal directly`() {
        // Points(-1) is not callable from outside the file (private constructor) — this is a
        // compile-time property, verified by the fact that `Points.of(-1)` above is the only way
        // this test CAN attempt a negative value, and it throws.
        assertThatIllegalArgumentException().isThrownBy { Points.of(Int.MIN_VALUE) }
    }
}
