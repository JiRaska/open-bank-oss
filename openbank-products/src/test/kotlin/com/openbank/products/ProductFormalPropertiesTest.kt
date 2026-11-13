// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.BonusRate
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.HookRule
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.IrVersion
import com.openbank.libs.product.ir.Predicate
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductMonotonicity
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.ir.Tier
import com.openbank.libs.testing.product.ProductArb
import com.openbank.libs.testing.product.assertMoneyConserved
import com.openbank.products.catalog.ReferenceProducts
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * reference product AND over generated revisions ([ProductArb.revision]). Today both are irVersion 1,
 * the only member of [IrVersion.SUPPORTED]; a second supported version needs its own generator here
 * (the first test fails if a reference product leaves the supported set). Gate
 * `product-ir-formal-properties` checks these assertions are present inside their named tests.
 *
 *  - PROPERTY termination: every evaluation stays within [IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION], and one
 *    that would exceed it is refused with [ProductEvaluationException].
 *  - PROPERTY balance-conservation: every `Post` balances per currency.
 *  - PROPERTY monotone-tiers: on the interpreter's own accrual, a reference product's marginal rate never
 *    falls from tier to tier and it never accrues less for a higher balance — for every revision
 *    [ProductMonotonicity] passes (IR v1 itself does not guarantee it; see that object).
 *  - PROPERTY determinism: the same input gives byte-identical canonical output.
 *  - PROPERTY purity: the compiled interpreter package references no clock, I/O, randomness, logging
 *    or metrics API, nor does the deposit arithmetic it delegates to, so the injected [ProductClock] is its
 *    only source of time.
 *  - v1 rounding is HALF_UP, pinned by golden values that sit exactly on a half.
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
                posts += out.count { it is Instruction.Post }
                assertThat(rev.irVersion).isIn(IrVersion.SUPPORTED)
                assertThat(out.size).isLessThanOrEqualTo(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
                assertMoneyConserved(out)
                // A fresh interpreter instance: determinism must not hang on per-instance state either.
                val again = ProductInterpreter(FinanceProductArithmetic).evaluate(rev, state, event, clock)
                assertThat(InstructionCanonical.write(again)).isEqualTo(InstructionCanonical.write(out))
            }
            assertThat(posts).describedAs("Post instructions checked for conservation").isGreaterThan(0)
        }

    // PROPERTY termination — the limit is a guard the interpreter enforces, not a number nothing reaches:
    // a revision built past the parser (one rule more than the limit allows) must be refused.
    @Test
    fun `an evaluation that would exceed the instruction limit is refused`() {
        val base = ReferenceProducts.termDeposit.compile()
        fun withOnOpenRules(n: Int) = base.copy(
            hooks = base.hooks + (
                Hook.ON_OPEN to (1..n).map {
                    HookRule("open.s$it", null, null, Action.Schedule(Hook.SCHEDULED, "T$it", Cadence.DAILY))
                }
                ),
        )
        val day = LocalDate.of(2026, 1, 1)
        val state = ProductState(base.currency, BigDecimal("100.00"), openedOn = day)
        val atLimit = interpreter.evaluate(
            withOnOpenRules(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION),
            state,
            ProductEvent(Hook.ON_OPEN),
            clockAt(day),
        )
        assertThat(atLimit).hasSize(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
        assertThatThrownBy {
            interpreter.evaluate(
                withOnOpenRules(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION + 1),
                state,
                ProductEvent(Hook.ON_OPEN),
                clockAt(day),
            )
        }.isInstanceOf(ProductEvaluationException::class.java).hasMessageContaining("exceeded")
    }

    // PROPERTY monotone-tiers — reference products, measured on the INTERPRETER's accrual Post: the
    // marginal rate (accrual per extra unit of balance) never falls from one tier to the next, and
    // the accrual never falls between any two sampled balances — sampled at every tier's `from`,
    // `from - 0.01`, `to` and interior points, so every tier and every edge is reached.
    @TestFactory
    fun `reference products - a higher balance never gets a lower tier rate or accrual`() =
        reference.filter { (_, rev) -> rev.interest != null }.map { (name, rev) ->
            DynamicTest.dynamicTest(name) {
                SAMPLE_DAYS.forEach { assertMonotoneTiers(rev, it, checkRates = true) }
            }
        }

    @Test
    fun `a rising bonus gate inside a band does not distort that band's measured rate`() {
        // Review repro: the bonus switches on at 20 000, between the [0, 50 000) band's two probe points.
        val rev = ReferenceProducts.termDeposit.compile()
        val terms = rev.interest!!
        val gated = rev.copy(
            interest = terms.copy(
                tiers = listOf(
                    Tier(BigDecimal("0.00"), BigDecimal("50000.00"), BigDecimal("0.03")),
                    Tier(BigDecimal("50000.00"), null, BigDecimal("0.05")),
                ),
                bonus = BonusRate(
                    "interest.bonus",
                    BigDecimal("0.10"),
                    Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.GTE, BigDecimal("20000")),
                ),
            ),
        )
        assertThat(ProductMonotonicity.isMonotone(gated)).isTrue()
        SAMPLE_DAYS.forEach { assertMonotoneTiers(gated, it, checkRates = true) }
    }

    @Test
    fun `the monotone-tier check rejects a 5 bp inversion in a one-unit tier`() {
        // A width-tolerance on a measured slope would hide this; the ordering is asserted on exact rates.
        val rev = ReferenceProducts.termDeposit.compile()
        val narrow = rev.copy(
            interest = rev.interest!!.copy(
                tiers = listOf(
                    Tier(BigDecimal("0.00"), BigDecimal("1.00"), BigDecimal("0.0300")),
                    Tier(BigDecimal("1.00"), BigDecimal("2.00"), BigDecimal("0.0295")),
                    Tier(BigDecimal("2.00"), null, BigDecimal("0.0300")),
                ),
            ),
        )
        assertThatThrownBy { assertMonotoneTiers(narrow, LocalDate.of(2026, 3, 1), checkRates = true) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("marginal rate of tier 1 vs tier 0")
    }

    @Test
    fun `the monotone-tier check rejects a revision whose upper tier pays less`() {
        // Known-negative through the real check: term-deposit with its two tier rates swapped.
        val rev = ReferenceProducts.termDeposit.compile()
        val terms = rev.interest!!
        val falling = rev.copy(
            interest = terms.copy(
                tiers = terms.tiers.zip(terms.tiers.reversed()) { t, r ->
                    t.copy(rate = r.rate)
                },
            ),
        )
        assertThatThrownBy { assertMonotoneTiers(falling, LocalDate.of(2026, 3, 1), checkRates = true) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("marginal rate")
    }

    // PROPERTY monotone-tiers — generated revisions. IR v1 does NOT make accrual monotone (a bonus or
    // accrue rule may be gated by `BALANCE LT x`, a tier may pay less than the one below; found by this
    // test at 3000 iterations, seed -6922326281093558709). ProductMonotonicity is the static check that
    // says which revisions are monotone, and the property is: every revision it passes accrues no less,
    // and no lower marginal rate per tier, for a higher balance. The check must also flag some.
    @Test
    fun `generated revisions - every revision the monotonicity check passes accrues monotonically`(): Unit =
        runBlocking {
            var passed = 0
            var flagged = 0
            checkAll(PropTestConfig(iterations = 300), ProductArb.revision, ProductArb.today) { rev, day ->
                if (rev.interest == null || accrueRule(rev) == null) return@checkAll
                if (!ProductMonotonicity.isMonotone(rev)) {
                    flagged++
                    return@checkAll
                }
                passed++
                assertMonotoneTiers(rev, day, checkRates = true)
            }
            assertThat(passed).describedAs("generated revisions the check passed").isGreaterThan(0)
            assertThat(flagged).describedAs("generated revisions the check flagged").isGreaterThan(0)
        }

    @Test
    fun `reference products pass the monotonicity check, and a bonus below a balance is flagged`() {
        assertThat(reference).allMatch { (_, rev) -> ProductMonotonicity.isMonotone(rev) }
        // Known-negative: the shape the property test found, on a real product.
        val rev = ReferenceProducts.savingsFixed.compile()
        val below = Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.LT, BigDecimal("7000.00"))
        val bonusBelow = rev.copy(
            interest = rev.interest!!.copy(
                bonus = rev.interest!!.bonus!!.copy(rate = BigDecimal("0.1000000000"), predicate = below),
            ),
        )
        assertThat(ProductMonotonicity.findings(bonusBelow)).hasSize(1)
        // ...and it really is non-monotone: 5 000 at 12.5 % accrues more than 10 000 at 2.5 %, so the accrual check fails.
        assertThatThrownBy { assertMonotoneTiers(bonusBelow, LocalDate.of(2026, 3, 1), checkRates = false) }
            .isInstanceOf(AssertionError::class.java)
    }

    // v1 arithmetic is frozen at HALF_UP (ADR-0352; IR v2 is where that may change). Two golden values
    // chosen to sit exactly on a half: HALF_EVEN would give 0.069444 and 0.12.
    @Test
    fun `v1 accrual and capitalisation round HALF_UP`() {
        val day = LocalDate.of(2026, 3, 1)
        val term = ReferenceProducts.termDeposit.compile()
        assertThat(interpreterAccrual(term, BigDecimal("1250.00"), day)).isEqualByComparingTo("0.069445")
        val savings = ReferenceProducts.savingsFixed.compile()
        val cap = interpreter.evaluate(
            savings,
            ProductState(savings.currency, BigDecimal("100.00"), BigDecimal("0.125"), openedOn = day.minusYears(1)),
            ProductEvent(Hook.SCHEDULED, tag = "CAPITALISATION"),
            clockAt(day),
        ).filterIsInstance<Instruction.Post>().flatMap { it.lines }
        // Capitalisation of 0.125 is 0.13 under HALF_UP; HALF_EVEN would post 0.12.
        assertThat(cap).isNotEmpty().allMatch { it.amount.compareTo(BigDecimal("0.13")) == 0 }
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
        val classes = compiledClasses(ProductInterpreter::class.java, "com/openbank/libs/product/")
        assertThat(classes.keys).describedAs("compiled classes under com/openbank/libs/product").hasSizeGreaterThan(10)
        // The arithmetic the interpreter delegates to runs inside every evaluation: it is held to the same rule.
        val finance = compiledClasses(FinanceProductArithmetic::class.java, "com/openbank/libs/finance/deposit/")
        assertThat(finance.keys).describedAs("compiled classes under com/openbank/libs/finance/deposit")
            .anyMatch { it.endsWith("FinanceProductArithmetic.class") }
            .anyMatch { it.endsWith("DepositInterest.class") }
        val all = classes + finance
        val findings = all.flatMap { (name, bytes) -> impureReferences(bytes).map { "$name -> $it" } }
        assertThat(findings).isEmpty()
    }

    @Test
    fun `the purity scan flags each ambient family on a sample that uses it`() {
        // Known-positives, one per family, compiled into this test module so each reference is certain.
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream(ImpureSample::class.java.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val found = impureReferences(bytes)
        listOf(
            "java/util/Date",
            "java/util/Calendar",
            "java/util/UUID.randomUUID",
            "java/lang/Math.random",
            "java/time/ZoneId.systemDefault",
            "java/util/TimeZone.getDefault",
            "java/time/Instant.now",
            "java/lang/System",
        ).forEach { family ->
            assertThat(found).describedAs(family).anyMatch { it == family || it.startsWith("$family$") }
        }
    }

    /** Deliberately impure: never called, only scanned. */
    @Suppress("unused")
    private object ImpureSample {
        fun date() = java.util.Date()
        fun calendar() = java.util.Calendar.getInstance()
        fun uuid() = java.util.UUID.randomUUID()
        fun random() = Math.random()
        fun zone() = java.time.ZoneId.systemDefault()
        fun timeZone() = java.util.TimeZone.getDefault()
        fun now() = java.time.Instant.now()
        fun nanos() = System.nanoTime()
    }

    @Test
    fun `the purity scan flags a class that reads the system clock`() {
        // Known-positive: this very test class calls LocalDate.of and System-free APIs only, so use a
        // JDK class that certainly reads ambient time.
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("java/time/LocalDate.class")!!.use { it.readBytes() }
        assertThat(impureReferences(bytes)).anyMatch { it.startsWith("java/time/Clock") }
    }

    private fun accrueRule(rev: ProductRevisionIr): HookRule? =
        rev.hooks[Hook.SCHEDULED].orEmpty().firstOrNull { it.action == Action.AccrueInterest }

    /**
     * The accrual the interpreter posts for one day on [balance]: the INTEREST_EXPENSE side of its Posts,
     * restricted to [ruleId] when given (the base rate's rule, so a bonus cannot distort a band's slope).
     */
    private fun interpreterAccrual(
        rev: ProductRevisionIr,
        balance: BigDecimal,
        day: LocalDate,
        ruleId: String? = null,
    ): BigDecimal {
        val tag = requireNotNull(accrueRule(rev)?.trigger) { "${rev.productCode} has no accrue rule" }
        val state = ProductState(rev.currency, balance, openedOn = day.minusYears(2))
        return interpreter.evaluate(rev, state, ProductEvent(Hook.SCHEDULED, tag = tag), clockAt(day))
            .filterIsInstance<Instruction.Post>()
            .filter { ruleId == null || it.ruleId == ruleId }
            .flatMap { it.lines }
            .filter { it.accountingClass == AccountingClass.INTEREST_EXPENSE }
            .fold(BigDecimal.ZERO) { acc, l -> acc + l.amount }
    }

    private fun assertMonotoneTiers(rev: ProductRevisionIr, day: LocalDate, checkRates: Boolean) {
        val terms = rev.interest!!
        val bands = terms.tiers.ifEmpty { listOf(Tier(BigDecimal.ZERO, null, terms.rate!!)) }
        val cent = BigDecimal("0.01")
        val points = bands.flatMap { t ->
            val width = (t.to ?: (t.from + BigDecimal("20000"))) - t.from
            listOfNotNull(
                t.from,
                (t.from - cent).takeIf { it.signum() >= 0 },
                t.to,
                t.from + width.divide(BigDecimal(4), 2, RoundingMode.DOWN),
                t.from + width.divide(BigDecimal(2), 2, RoundingMode.DOWN),
            )
        }.distinct().sorted()
        val accruals = points.map { it to interpreterAccrual(rev, it, day) }
        accruals.zipWithNext().forEach { (lo, hi) ->
            assertThat(hi.second)
                .describedAs("${rev.productCode} accrual at ${hi.first} vs ${lo.first} on $day")
                .isGreaterThanOrEqualTo(lo.second)
        }
        // Every tier is reached, and the interpreter's base-rate slope inside each band matches that band's
        // authored rate (a bonus is excluded: its own gate may switch on inside a band).
        val measured = bands.map { t ->
            val width = (t.to ?: (t.from + BigDecimal("20000"))) - t.from
            val a = t.from + width.divide(BigDecimal(4), 2, RoundingMode.DOWN)
            val b = t.from + width.divide(BigDecimal(2), 2, RoundingMode.DOWN)
            val span = b - a
            val got = interpreterAccrual(rev, b, day, terms.ruleId) - interpreterAccrual(rev, a, day, terms.ruleId)
            if (span.signum() > 0) {
                val want = FinanceProductArithmetic.dailyAccrual(
                    span,
                    t.rate,
                    terms.dayCount,
                    day,
                    terms.accrualRounding,
                )
                // Three accruals rounded at scale 6, and daily rates rounded at scale 10 over at most b.
                val tolerance = BigDecimal("0.0000015") + b.multiply(BigDecimal("1E-10"))
                assertThat((got - want).abs())
                    .describedAs("${rev.productCode} tier from ${t.from}: interpreter slope vs rate ${t.rate} on $day")
                    .isLessThanOrEqualTo(tolerance)
            }
            t
        }
        assertThat(measured).describedAs("tiers whose marginal rate was measured").hasSize(bands.size)
        if (checkRates) {
            // Exact ordering on the rates the slope was just tied to: no tolerance to hide an inversion.
            bands.zipWithNext().forEachIndexed { i, (lo, hi) ->
                assertThat(hi.rate)
                    .describedAs("${rev.productCode} marginal rate of tier ${i + 1} vs tier $i")
                    .isGreaterThanOrEqualTo(lo.rate)
            }
        }
    }

    private fun clockAt(day: LocalDate) = ProductClock(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun compiledClasses(anchor: Class<*>, prefix: String): Map<String, ByteArray> {
        val location = Path.of(anchor.protectionDomain.codeSource.location.toURI())
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
                (owner.startsWith("java/time/") && name == "now") || (owner to name) in FORBIDDEN_MEMBERS
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
        // A leap day, a month end and a year end: ACT_ACT and THIRTY_360 behave differently on each.
        val SAMPLE_DAYS = listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 6, 30), LocalDate.of(2026, 12, 31))

        val FORBIDDEN_CLASSES = listOf(
            "java/lang/System", "java/lang/Runtime", "java/lang/Thread", "java/lang/ProcessBuilder",
            "java/time/Clock", "java/time/InstantSource",
            "java/io/File", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/PrintStream",
            "java/nio/file", "java/net", "java/util/Random", "java/util/concurrent/ThreadLocalRandom",
            "java/security/SecureRandom", "kotlin/random", "kotlin/io/ConsoleKt", "kotlin/io/FilesKt",
            "java/util/logging", "org/slf4j", "org/jboss/logging", "io/micrometer", "io/opentelemetry",
            // Legacy ambient-time types: every one of them reads the system clock or default zone.
            "java/util/Date", "java/util/Calendar", "java/util/GregorianCalendar", "java/util/TimeZone",
        )

        /** Ambient members on otherwise-allowed types: randomness and the default zone. */
        val FORBIDDEN_MEMBERS = setOf(
            "java/util/UUID" to "randomUUID",
            "java/lang/Math" to "random",
            "java/lang/StrictMath" to "random",
            "java/time/ZoneId" to "systemDefault",
            "java/util/TimeZone" to "getDefault",
        )
    }
}
