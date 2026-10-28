// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.tools.ToolProvider

/** Compile an old consumer against its old API, then execute its unchanged bytecode on today's library. */
class CreditQuoteBinaryCompatibilityTest {
    @Test
    fun `a consumer compiled against the original four argument quote links without recompilation`(@TempDir dir: Path) {
        compileHistoricalConsumer(dir)
        val request = CreditQuoteRequest(Money.of(BigDecimal("1000"), "EUR"), 12, BigDecimal("0.05"))
        val now = Instant.parse("2026-01-01T00:00:00Z")
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val quote = loader.loadClass("HistoricalQuoteConsumer")
                .getMethod(
                    "run",
                    CreditQuoteRequest::class.java,
                    Instant::class.java,
                    Duration::class.java,
                    LocalDate::class.java,
                )
                .invoke(null, request, now, Duration.ofDays(7), LocalDate.parse("2026-02-01")) as CreditQuote
            assertThat(quote.principal).isEqualTo(request.principal)
            assertThat(quote.validUntil).isEqualTo(now.plus(Duration.ofDays(7)))
            assertThat(quote.monthlyPayment.amount).isPositive()
        }
    }

    private fun compileHistoricalConsumer(dir: Path) {
        val packageDir = Files.createDirectories(dir.resolve("com/openbank/libs/lending"))
        val stub = packageDir.resolve("CreditQuoteCalculator.java")
        Files.writeString(
            stub,
            """
            package com.openbank.libs.lending;
            public class CreditQuoteCalculator {
                public static final CreditQuoteCalculator INSTANCE = new CreditQuoteCalculator();
                public CreditQuote quote(CreditQuoteRequest request, java.time.Instant now,
                    java.time.Duration validity, java.time.LocalDate firstDue) { return null; }
            }
            """.trimIndent(),
        )
        val consumer = dir.resolve("HistoricalQuoteConsumer.java")
        Files.writeString(
            consumer,
            """
            import com.openbank.libs.lending.*;
            public class HistoricalQuoteConsumer {
                public static CreditQuote run(CreditQuoteRequest request, java.time.Instant now,
                    java.time.Duration validity, java.time.LocalDate firstDue) {
                    return CreditQuoteCalculator.INSTANCE.quote(request, now, validity, firstDue);
                }
            }
            """.trimIndent(),
        )
        val classpath = listOf(CreditQuote::class.java, Money::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "ABI proof requires a JDK" }
        assertThat(
            compiler.run(
                null,
                null,
                null,
                "-classpath",
                classpath,
                "-d",
                dir.toString(),
                stub.toString(),
                consumer.toString(),
            ),
        )
            .isZero()
        // Only the consumer bytecode survives. The current library must satisfy its old invoke descriptor.
        Files.delete(packageDir.resolve("CreditQuoteCalculator.class"))
    }
}
