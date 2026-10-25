// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.purity

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Negative cases first (this repo's rule: a gate that has only ever passed is unfalsified). Every
 * forbidden shape is proven to FLAG before the legitimate interpreter shape is proven to pass.
 */
class ProductIrPurityRuleTest {

    private val rule = ProductIrPurityRule()

    private fun lint(body: String, pkg: String = "com.openbank.libs.product.ir") =
        rule.compileAndLint("package $pkg\n\n$body")

    @Test
    fun `MUST FAIL - a java io import under the product package`() {
        val findings = lint(
            """
            import java.io.File

            class Loader { fun load() = File("x").readText() }
            """.trimIndent(),
        )
        assertThat(findings).isNotEmpty
        assertThat(findings.first().message).contains("import of 'java.io.File' is not allowed")
    }

    @Test
    fun `MUST FAIL - every forbidden import family`() {
        val imports = listOf(
            "java.nio.file.Files", "java.net.URI", "java.time.Clock", "java.lang.System", "org.jboss.logging.Logger",
            "org.slf4j.LoggerFactory", "io.micrometer.core.instrument.MeterRegistry", "java.util.logging.Logger",
            "io.opentelemetry.api.trace.Tracer", "kotlin.io.println", "java.util.concurrent.Executors",
        )
        for (imp in imports) {
            val findings = lint("import $imp\n\nclass X")
            assertThat(findings).withFailMessage("expected '$imp' to be flagged").isNotEmpty
        }
    }

    @Test
    fun `MUST FAIL - ambient clock and process reads without an import`() {
        val cases = listOf(
            "class X { fun t() = java.time.Instant.now() }",
            "import java.time.Instant\nclass X { fun t() = Instant.now() }",
            "import java.time.LocalDate\nclass X { fun t() = LocalDate.now() }",
            "class X { fun t() = System.currentTimeMillis() }",
            "class X { fun t() = java.lang.System.getenv(\"HOME\") }",
            "class X { fun t() = Runtime.getRuntime() }",
            "class X { fun t() = Thread.sleep(1) }",
            "class X { fun t() { println(\"x\") } }",
            "class X { fun t() { print(\"x\") } }",
        )
        for (src in cases) {
            assertThat(lint(src)).withFailMessage("expected to be flagged: $src").isNotEmpty
        }
    }

    @Test
    fun `MUST FAIL - the base product package is in scope too`() {
        assertThat(lint("import java.net.URL\n\nclass X", pkg = "com.openbank.libs.product")).isNotEmpty
    }

    @Test
    fun `passes the pure interpreter shape`() {
        val findings = lint(
            """
            import java.math.BigDecimal
            import java.time.Instant
            import java.time.LocalDate
            import java.time.ZoneId
            import com.openbank.libs.audit.CanonicalJson

            data class ProductClock(val now: Instant, val zone: ZoneId) {
                fun today(): LocalDate = now.atZone(zone).toLocalDate()
            }

            class Interpreter {
                fun evaluate(balance: BigDecimal, clock: ProductClock): List<String> {
                    val today = clock.today()
                    val next = today.plusDays(1)
                    return listOf(CanonicalJson.write(mapOf("d" to next.toString())), balance.toPlainString())
                }
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `ignores the same shapes outside the product package`() {
        val src = "import java.io.File\nimport java.time.Instant\n\n" +
            "class X { fun t() = Instant.now().toString() + File(\"x\").name + System.nanoTime() }"
        assertThat(lint(src, pkg = "com.openbank.libs.audit")).isEmpty()
        assertThat(lint(src, pkg = "com.openbank.libs.productcatalog")).isEmpty()
    }
}
