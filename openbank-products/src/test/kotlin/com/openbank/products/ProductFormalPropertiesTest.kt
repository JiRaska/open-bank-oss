// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.InterestTerms
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.IrVersion
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.RateType
import com.openbank.libs.product.ir.Tier
import com.openbank.libs.testing.product.ProductArb
import com.openbank.libs.testing.product.assertMoneyConserved
import com.openbank.products.catalog.ReferenceProducts
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.DataInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.jar.JarFile

/**
 * ADR-0352 §9 phase 6: the five formal properties of the product interpreter, held over every
 * reference product AND over generated revisions ([ProductArb.revision]) under every supported
 * `irVersion`. Gate `product-ir-formal-properties` keeps the property names below present and enabled.
 *
 *  - PROPERTY termination: every evaluation stays within [IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION].
 *  - PROPERTY balance-conservation: every `Post` balances per currency.
 *  - PROPERTY monotone-tiers: a higher balance never gets a lower tier rate, nor a lower daily accrual.
 *  - PROPERTY determinism: the same input gives byte-identical canonical output.
 *  - PROPERTY purity: the compiled interpreter package references no clock, I/O, randomness, logging
 *    or metrics API, so the injected [ProductClock] is its only source of time.
 */
class ProductFormalPropertiesTest {

    private val interpreter = ProductInterpreter(FinanceProductArithmetic)

    private val reference: List<Pair<String, ProductRevisionIr>> =
        ReferenceProducts.all.map { it.name to it.compile() }

    @Test
    fun `every reference product is authored under a supported irVersion`() {
        assertThat(reference).allMatch { (_, rev) -> rev.irVersion in IrVersion.SUPPORTED }
    }

    // PROPERTY termination, balance-conservation, determinism — generated revisions.
    @Test
    fun `generated revisions terminate within the limit, conserve money and evaluate deterministically`(): Unit =
        runBlocking {
            val cases = ProductArb.revision.flatMap { rev ->
                ProductArb.today.flatMap { today ->
                    ProductArb.state(rev.currency, today).flatMap { state ->
                        ProductArb.event(rev).map { event -> Triple(rev, state to today, event) }
                    }
                }
            }
            var posts = 0
            checkAll(PropTestConfig(iterations = 300), cases) { (rev, stateAndDay, event) ->
                val (state, today) = stateAndDay
                val clock = clockAt(today)
                val out = interpreter.evaluate(rev, state, event, clock)
                posts += out.count { it is com.openbank.libs.product.ir.Instruction.Post }
                assertThat(rev.irVersion).isIn(IrVersion.SUPPORTED)
                assertThat(out.size).isLessThanOrEqualTo(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
                assertMoneyConserved(out)
                // A fresh interpreter instance: determinism must not hang on per-instance state either.
                val again = ProductInterpreter(FinanceProductArithmetic).evaluate(rev, state, event, clock)
                assertThat(InstructionCanonical.write(again)).isEqualTo(InstructionCanonical.write(out))
            }
            assertThat(posts).describedAs("Post instructions checked for conservation").isGreaterThan(0)
        }

    // PROPERTY monotone-tiers — reference products: the IR's tier schedule itself is non-decreasing.
    @TestFactory
    fun `reference products - a higher balance never gets a lower tier rate or accrual`() =
        reference.map { (name, rev) ->
            DynamicTest.dynamicTest(name) {
                val terms = rev.interest ?: return@dynamicTest
                if (terms.rateType == RateType.TIERED) {
                    assertThat(terms.tiers.zipWithNext()).allMatch { (lo, hi) -> hi.rate >= lo.rate }
                }
                checkMonotone(terms, checkTierRate = terms.rateType == RateType.TIERED)
            }
        }

    // PROPERTY monotone-tiers — generated revisions: whatever the authored rates, marginal accrual
    // never falls as the balance rises (each extra unit earns a non-negative rate).
    @Test
    fun `generated revisions - daily accrual is non-decreasing in balance`(): Unit = runBlocking {
        var tiered = 0
        checkAll(PropTestConfig(iterations = 200), ProductArb.revision) { rev ->
            val terms = rev.interest ?: return@checkAll
            if (terms.rateType == RateType.TIERED) tiered++
            checkMonotone(terms, checkTierRate = false, iterations = 20)
        }
        assertThat(tiered).describedAs("generated TIERED revisions").isGreaterThan(0)
    }

    @Test
    fun `the monotone-tier check rejects a decreasing schedule`() {
        // Known-negative for the property above: a schedule whose upper tier pays less must be caught.
        val falling = listOf(
            Tier(BigDecimal("0"), BigDecimal("1000"), BigDecimal("0.05")),
            Tier(BigDecimal("1000"), null, BigDecimal("0.01")),
        )
        assertThat(tierRateAt(falling, BigDecimal("1500"))).isLessThan(tierRateAt(falling, BigDecimal("500")))
    }

    // PROPERTY determinism — reference products, one golden-like input per product evaluated twice.
    @TestFactory
    fun `reference products - same input, byte-identical output`() = reference.map { (name, rev) ->
        DynamicTest.dynamicTest(name) {
            runBlocking {
                val cases = ProductArb.today.flatMap { today ->
                    ProductArb.state(rev.currency, today).flatMap { s ->
                        ProductArb.event(rev).map { e -> Triple(today, s, e) }
                    }
                }
                checkAll(PropTestConfig(iterations = 100), cases) { (today, state, event) ->
                    val a = interpreter.evaluate(rev, state, event, clockAt(today))
                    val b = interpreter.evaluate(rev, state, event, clockAt(today))
                    assertThat(InstructionCanonical.write(b)).isEqualTo(InstructionCanonical.write(a))
                    assertThat(a.size).isLessThanOrEqualTo(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
                    assertMoneyConserved(a)
                }
            }
        }
    }

    // PROPERTY purity — read the COMPILED package, which also sees calls a source grep cannot
    // (inlined stdlib, string templates, generated code).
    @Test
    fun `the compiled interpreter package references no ambient clock, IO, randomness, logging or metrics`() {
        val classes = productPackageClasses()
        assertThat(classes.keys).describedAs("compiled classes under com/openbank/libs/product").hasSizeGreaterThan(10)
        val findings = classes.flatMap { (name, bytes) -> impureReferences(bytes).map { "$name -> $it" } }
        assertThat(findings).isEmpty()
    }

    @Test
    fun `the purity scan flags a class that reads the system clock`() {
        // Known-positive: this very test class calls LocalDate.of and System-free APIs only, so use a
        // JDK class that certainly reads ambient time.
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("java/time/LocalDate.class")!!.use { it.readBytes() }
        assertThat(impureReferences(bytes)).anyMatch { it.startsWith("java/time/Clock") }
    }

    private fun checkMonotone(terms: InterestTerms, checkTierRate: Boolean, iterations: Int = 200): Unit = runBlocking {
        val pair = Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("1000000.00")).flatMap { a ->
            Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("1000000.00")).map { b ->
                listOf(a, b).map { it.setScale(2, RoundingMode.HALF_UP) }.sorted()
            }
        }
        checkAll(PropTestConfig(iterations = iterations), pair, ProductArb.today) { (lo, hi), day ->
            if (checkTierRate) {
                assertThat(
                    tierRateAt(terms.tiers, hi),
                ).isGreaterThanOrEqualTo(tierRateAt(terms.tiers, lo))
            }
            assertThat(accrual(terms, hi, day)).isGreaterThanOrEqualTo(accrual(terms, lo, day))
        }
    }

    private fun accrual(terms: InterestTerms, balance: BigDecimal, day: LocalDate): BigDecimal = when (terms.rateType) {
        RateType.FIXED -> FinanceProductArithmetic.dailyAccrual(
            balance,
            terms.rate!!,
            terms.dayCount,
            day,
            terms.accrualRounding,
        )
        RateType.TIERED -> FinanceProductArithmetic.tieredDailyAccrual(
            balance,
            terms.tiers,
            terms.dayCount,
            day,
            terms.accrualRounding,
        )
    }

    private fun tierRateAt(tiers: List<Tier>, balance: BigDecimal): BigDecimal = tiers.last { balance >= it.from }.rate

    private fun clockAt(day: LocalDate) = ProductClock(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun productPackageClasses(): Map<String, ByteArray> {
        val location = Path.of(ProductInterpreter::class.java.protectionDomain.codeSource.location.toURI())
        val prefix = "com/openbank/libs/product/"
        return if (Files.isDirectory(location)) {
            Files.walk(location.resolve(prefix)).use { s ->
                s.filter { it.toString().endsWith(".class") }.toList()
                    .associate { location.relativize(it).toString() to Files.readAllBytes(it) }
            }
        } else {
            JarFile(location.toFile()).use { jar ->
                jar.entries().toList().filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }
                    .associate { e -> e.name to jar.getInputStream(e).use { it.readBytes() } }
            }
        }
    }

    /** Class and member references in a class file's constant pool that an interpreter may not make. */
    private fun impureReferences(bytes: ByteArray): List<String> {
        val pool = ConstantPool.read(bytes)
        val classes = pool.classNames()
        val members = pool.memberRefs()
        return classes.filter { c ->
            FORBIDDEN_CLASSES.any { c == it || c.startsWith("$it/") || c.startsWith("$it$") }
        } +
            members.filter { (owner, name) ->
                owner.startsWith("java/time/") && name == "now"
            }.map { "${it.first}.${it.second}" }
    }

    /** Minimal JVMS §4.4 constant-pool reader: just enough to resolve class names and member refs. */
    private class ConstantPool(
        private val tags: IntArray,
        private val a: IntArray,
        private val b: IntArray,
        private val utf: Array<String?>,
    ) {
        fun classNames(): List<String> = tags.indices.filter { tags[it] == CLASS }.mapNotNull { utf[a[it]] }

        fun memberRefs(): List<Pair<String, String>> = tags.indices
            .filter { tags[it] == FIELD || tags[it] == METHOD || tags[it] == IMETHOD }
            .map { utf[a[a[it]]].orEmpty() to utf[a[b[it]]].orEmpty() }

        companion object {
            const val CLASS = 7
            const val FIELD = 9
            const val METHOD = 10
            const val IMETHOD = 11

            fun read(bytes: ByteArray): ConstantPool {
                val input = DataInputStream(bytes.inputStream())
                check(input.readInt() == 0xCAFEBABE.toInt())
                input.readUnsignedShort()
                input.readUnsignedShort()
                val n = input.readUnsignedShort()
                val tags = IntArray(n)
                val a = IntArray(n)
                val b = IntArray(n)
                val utf = arrayOfNulls<String>(n)
                var i = 1
                while (i < n) {
                    val tag = input.readUnsignedByte()
                    tags[i] = tag
                    when (tag) {
                        1 -> utf[i] = input.readUTF()
                        CLASS, 8, 16, 19, 20 -> a[i] = input.readUnsignedShort()
                        15 -> {
                            input.readUnsignedByte()
                            a[i] = input.readUnsignedShort()
                        }
                        FIELD, METHOD, IMETHOD, 12, 17, 18 -> {
                            a[i] = input.readUnsignedShort()
                            b[i] =
                                input.readUnsignedShort()
                        }
                        3, 4 -> input.readInt()
                        5, 6 -> {
                            input.readLong()
                            i++
                        }
                        else -> error("unknown constant-pool tag $tag")
                    }
                    i++
                }
                return ConstantPool(tags, a, b, utf)
            }
        }
    }

    private companion object {
        val FORBIDDEN_CLASSES = listOf(
            "java/lang/System", "java/lang/Runtime", "java/lang/Thread", "java/lang/ProcessBuilder",
            "java/time/Clock", "java/time/InstantSource",
            "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/PrintStream",
            "java/nio/file", "java/net", "java/util/Random", "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom", "kotlin/random", "kotlin/io/ConsoleKt", "kotlin/io/FilesKt",
            "java/util/logging", "org/slf4j", "org/jboss/logging", "io/micrometer", "io/opentelemetry",
        )
    }
}
