// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.AccountEvent
import com.openbank.libs.product.ir.BalanceSample
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.ir.ProductStateTransition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One golden scenario (ADR-0352 §8): a revision, an initial state, a sequence of timed events
 * and, per event, the instruction list the interpreter must produce. Authored as JSON next to the
 * products it describes (JSON files under `product-golden/`, moving to `openbank-products` in phase 2).
 *
 * `expected` instructions are written in the [InstructionCanonical] shape WITHOUT `revisionHash`
 * (the runner checks separately that every emitted instruction carries the revision's hash), and
 * amounts may be authored at any scale — they are normalised to the canonical scale before the
 * byte comparison.
 */
data class GoldenScenario(
    val name: String,
    val description: String,
    val revision: ProductRevisionIr,
    val zone: ZoneId,
    val initialState: ProductState,
    val steps: List<Step>,
) {
    data class Step(
        val at: Instant,
        val event: ProductEvent,
        /** Canonical JSON (keys sorted, hash-free) of the instruction list this step must produce. */
        val expectedCanonical: String,
    )

    companion object {
        /** Reads a scenario from its JSON text. The revision inside is parsed and validated like any other IR. */
        fun parse(json: String): GoldenScenario {
            @Suppress("UNCHECKED_CAST")
            val root = IrJson.parse(json) as Map<String, Any?>
            val revision = ProductIrParser.fromTree(root.getValue("revision"))
            @Suppress("UNCHECKED_CAST")
            val clock = root["clock"] as Map<String, Any?>? ?: emptyMap()
            val zone = ZoneId.of(clock["zone"] as String? ?: "UTC")
            @Suppress("UNCHECKED_CAST")
            val stateTree = root.getValue("state") as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val steps = (root.getValue("steps") as List<Map<String, Any?>>).map { step(it) }
            return GoldenScenario(
                name = root.getValue("name") as String,
                description = root["description"] as String? ?: "",
                revision = revision,
                zone = zone,
                initialState = state(stateTree),
                steps = steps,
            )
        }

        private fun dec(v: Any?): BigDecimal = when (v) {
            is BigDecimal -> v
            is String -> BigDecimal(v)
            null -> BigDecimal.ZERO
            else -> error("not a decimal: $v")
        }

        @Suppress("UNCHECKED_CAST")
        private fun state(t: Map<String, Any?>): ProductState = ProductState(
            currency = t.getValue("currency") as String,
            balance = dec(t["balance"]),
            accruedInterest = dec(t["accruedInterest"]),
            openedOn = LocalDate.parse(t.getValue("openedOn") as String),
            segment = t["segment"] as String?,
            monthlyTurnover = t["monthlyTurnover"]?.let { dec(it) },
            aggregatePocketBalance = t["aggregatePocketBalance"]?.let { dec(it) },
            events = (t["events"] as List<Map<String, Any?>>? ?: emptyList()).map {
                AccountEvent(EventKind.valueOf(it.getValue("kind") as String), LocalDate.parse(it.getValue("at") as String), dec(it["amount"]))
            },
            balanceSamples = (t["balanceSamples"] as List<Map<String, Any?>>? ?: emptyList()).map {
                BalanceSample(LocalDate.parse(it.getValue("on") as String), dec(it["balance"]))
            },
            parameters = (t["parameters"] as Map<String, Any?>? ?: emptyMap()).mapValues { dec(it.value) },
        )

        @Suppress("UNCHECKED_CAST")
        private fun step(t: Map<String, Any?>): Step {
            val hook = ProductIrParser.HOOK_KEYS.getValue(t.getValue("hook") as String)
            val posting = (t["posting"] as Map<String, Any?>?)?.let {
                Posting(
                    EventKind.valueOf(it.getValue("kind") as String),
                    PostingDirection.valueOf(it.getValue("direction") as String),
                    dec(it["amount"]),
                )
            }
            val event = ProductEvent(
                hook = hook,
                tag = t["tag"] as String?,
                posting = posting,
                parameter = t["parameter"] as String?,
                newValue = t["newValue"]?.let { dec(it) },
            )
            val expected = (t["expected"] as List<Any?>? ?: emptyList()).map { normaliseExpected(it) }
            return Step(Instant.parse(t.getValue("at") as String), event, CanonicalJson.write(expected))
        }

        /** Brings an authored expected instruction onto the canonical scales so `0.6849` and `0.684900` compare equal. */
        private fun normaliseExpected(v: Any?): Any? = when (v) {
            is Map<*, *> -> v.entries.associate { (k, value) ->
                val key = k as String
                key to when {
                    key == "amount" && (value is String || value is BigDecimal) -> dec(value).setScale(IrLimits.INSTRUCTION_AMOUNT_SCALE).toPlainString()
                    key == "value" && (value is String || value is BigDecimal) -> dec(value).setScale(IrLimits.RATE_SCALE).toPlainString()
                    key == "irVersion" && value is BigDecimal -> value.toInt()
                    else -> normaliseExpected(value)
                }
            }
            is List<*> -> v.map { normaliseExpected(it) }
            else -> v
        }
    }
}

/** Result of one step: the canonical bytes the interpreter produced and what the scenario expected. */
data class GoldenStepResult(
    val index: Int,
    val event: ProductEvent,
    val instructions: List<Instruction>,
    val actualCanonical: String,
    val expectedCanonical: String,
) {
    val matches: Boolean get() = actualCanonical == expectedCanonical
}

/**
 * Runs a [GoldenScenario] through the one pure interpreter with the production arithmetic,
 * applying [ProductStateTransition] between steps. [run] never asserts; [assertPasses] does.
 */
class GoldenScenarioRunner(private val interpreter: ProductInterpreter = ProductInterpreter(FinanceProductArithmetic)) {

    fun run(scenario: GoldenScenario): List<GoldenStepResult> {
        var state = scenario.initialState
        return scenario.steps.mapIndexed { i, step ->
            val clock = ProductClock(step.at, scenario.zone)
            val out = interpreter.evaluate(scenario.revision, state, step.event, clock)
            val today = clock.today()
            val rejected = out.any { it is Instruction.Reject }
            state = ProductStateTransition.apply(state, out, today)
            val posting = step.event.posting
            if (posting != null && step.event.hook == Hook.PRE_POSTING && !rejected) {
                state = ProductStateTransition.applyPosting(state, posting, today)
            }
            GoldenStepResult(i, step.event, out, stripHash(out), step.expectedCanonical)
        }
    }

    /** Canonical instruction JSON without `revisionHash`, the form a scenario's `expected` is written in. */
    fun stripHash(instructions: List<Instruction>): String =
        CanonicalJson.write(instructions.map { InstructionCanonical.tree(it) - "revisionHash" })

    fun assertPasses(scenario: GoldenScenario) {
        val results = run(scenario)
        val failures = ArrayList<String>()
        for (r in results) {
            assertMoneyConserved(r.instructions)
            r.instructions.filter { it.revisionHash != scenario.revision.revisionHash || it.irVersion != scenario.revision.irVersion }
                .forEach { failures += "step ${r.index}: ${it.ruleId} carries hash ${it.revisionHash}/v${it.irVersion}, revision is ${scenario.revision.revisionHash}/v${scenario.revision.irVersion}" }
            if (!r.matches) failures += "step ${r.index} (${r.event.hook}${r.event.tag?.let { " $it" } ?: ""}):\n    expected ${r.expectedCanonical}\n    actual   ${r.actualCanonical}"
        }
        if (failures.isNotEmpty()) throw AssertionError("golden scenario '${scenario.name}' failed:\n  " + failures.joinToString("\n  "))
    }
}

/** The committed golden set: every scenario file under `product-golden/` on the classpath, listed in `product-golden/index.txt`. */
object GoldenScenarios {
    private const val INDEX = "/product-golden/index.txt"

    fun names(): List<String> = text(INDEX).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun load(name: String): GoldenScenario = GoldenScenario.parse(text("/product-golden/$name"))

    fun all(): List<GoldenScenario> = names().map { load(it) }

    private fun text(path: String): String =
        checkNotNull(GoldenScenarios::class.java.getResourceAsStream(path)) { "missing classpath resource $path" }
            .readBytes().toString(Charsets.UTF_8)
}
