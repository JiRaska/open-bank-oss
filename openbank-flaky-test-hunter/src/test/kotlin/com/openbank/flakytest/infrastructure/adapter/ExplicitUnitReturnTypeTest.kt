// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExplicitUnitReturnTypeTest {

    @Test
    fun `adds explicit Unit only to one expression-body runBlocking function`() {
        val builder = "runBlocking"
        val source = """
            class SafeMechanicalRepair {
                fun lostTest() = $builder { 1 }
            }
        """.trimIndent()

        val repaired = ManualBoundedUnitReturnType.apply(source)

        assertThat(repaired).contains("fun lostTest(): Unit = runBlocking { 1 }")
    }

    @Test
    fun `refuses a file containing multiple possible repairs`() {
        val builder = "runBlocking"
        val source = """
            class AmbiguousRepair {
                fun first() = $builder { 1 }
                fun second() = $builder { 2 }
            }
        """.trimIndent()

        assertThat(ManualBoundedUnitReturnType.apply(source)).isNull()
    }

    @Test
    fun `refuses a file with no expression-body runBlocking function`() {
        assertThat(ManualBoundedUnitReturnType.apply("fun safe(): Unit = runBlocking { 1 }")).isNull()
    }

    @Test
    fun `refuses a function that already declares a non-Unit return type`() {
        assertThat(ManualBoundedUnitReturnType.apply("fun unsafe(): String = runBlocking { \"value\" }")).isNull()
    }

    @Test
    fun `refuses a braced body containing the expression text`() {
        val source = """
            fun notAnExpressionBody() {
                val documentation = " = runBlocking {"
            }
        """.trimIndent()

        assertThat(ManualBoundedUnitReturnType.apply(source)).isNull()
    }

    @Test
    fun `accepts only a canonical own test-source path`() {
        assertThat(BoundedTestPath.isSafe("openbank-flaky-test-hunter/src/test/kotlin/example/SafeTest.kt")).isTrue()
        assertThat(
            BoundedTestPath.isSafe(
                "openbank-flaky-test-hunter/src/test/kotlin/../../src/main/kotlin/Unsafe.kt",
            ),
        ).isFalse()
        assertThat(BoundedTestPath.isSafe("/openbank-flaky-test-hunter/src/test/kotlin/Unsafe.kt")).isFalse()
        assertThat(
            BoundedTestPath.isSafe(
                "openbank-flaky-test-hunter/src/test/kotlin/%2e%2e/%2e%2e/src/main/kotlin/Unsafe.kt",
            ),
        ).isFalse()
    }
}
