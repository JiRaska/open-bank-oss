// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.toolkit

import com.openbank.libs.product.ir.IrFixtures
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductIrValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** The facade behind both `obp` and the MCP product tools (ADR-0352 §11-§12). Negative cases first. */
class ProductToolkitTest {

    private val rev = IrFixtures.savings()

    private val portfolio = """
        {"zone":"UTC","accounts":[{"id":"a1",
          "state":{"currency":"EUR","balance":"500.00","openedOn":"2026-01-01"},
          "steps":[{"at":"2026-01-05T23:00:00Z","hook":"scheduled","tag":"ACCRUAL"},
                   {"at":"2026-02-05T23:00:00Z","hook":"scheduled","tag":"ACCRUAL"}]}]}
    """.trimIndent()

    @Test
    fun `lint reports a duplicate ruleId and an unknown key as problems, never as ok`() {
        val dup = IrFixtures.savingsJson.replace("\"scheduled.fee\"", "\"scheduled.accrue\"")
        val report = ProductToolkit.lint(dup)
        assertThat(report["ok"]).isEqualTo(false)
        assertThat(report.toString()).contains("duplicate ruleId")
        assertThat(ProductToolkit.lint("{\"irVersion\":1,\"bogus\":true}")["ok"]).isEqualTo(false)
    }

    @Test
    fun `lint of a valid document carries its hash and ruleId count`() {
        val report = ProductToolkit.lint(IrFixtures.savingsJson)
        assertThat(report["ok"]).isEqualTo(true)
        assertThat(report["revisionHash"]).isEqualTo(rev.revisionHash)
        assertThat(report["ruleIds"] as Int).isEqualTo(ProductToolkit.ruleIdPaths(rev).size).isPositive()
    }

    @Test
    fun `compile refuses an invalid document and canonicalises a valid one, deterministically`() {
        assertThatThrownBy { ProductToolkit.compile("{}") }.isInstanceOf(ProductIrValidationException::class.java)
        val pretty = IrJson.parse(IrFixtures.savingsJson).toString() // not canonical text
        assertThat(pretty).isNotEqualTo(ProductToolkit.compile(rev).canonicalJson)
        val a = ProductToolkit.compile(IrFixtures.savingsJson)
        val b = ProductToolkit.compile(IrFixtures.savingsJson)
        assertThat(a).isEqualTo(b)
        assertThat(a.revisionHash).isEqualTo(rev.revisionHash)
        assertThat(ProductToolkit.write(a.tree())).isEqualTo(ProductToolkit.write(b.tree()))
    }

    @Test
    fun `simulate rejects a malformed fixture and an unknown hook`() {
        assertThatThrownBy { ProductToolkit.simulate(rev, "[]", IrFixtures.StubArithmetic) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val badHook = portfolio.replace("\"scheduled\"", "\"nope\"")
        assertThatThrownBy { ProductToolkit.simulate(rev, badHook, IrFixtures.StubArithmetic) }
            .hasMessageContaining("unknown hook")
    }

    @Test
    fun `simulate replays steps inside the window only and is byte-deterministic`() {
        val all = ProductToolkit.write(ProductToolkit.simulate(rev, portfolio, IrFixtures.StubArithmetic))
        assertThat(all).contains("\"ruleId\":\"scheduled.accrue\"", rev.revisionHash)
        assertThat(all).isEqualTo(ProductToolkit.write(ProductToolkit.simulate(rev, portfolio, IrFixtures.StubArithmetic)))
        val window = ProductToolkit.simulate(
            rev,
            portfolio,
            IrFixtures.StubArithmetic,
            from = Instant.parse("2026-02-01T00:00:00Z"),
            to = Instant.parse("2026-03-01T00:00:00Z"),
        )
        @Suppress("UNCHECKED_CAST")
        val steps = ((window["accounts"] as List<Map<String, Any?>>).single()["steps"] as List<*>)
        assertThat(steps).hasSize(1)
    }

    @Test
    fun `explain refuses an unknown rule and a foreign instruction, maps a known one to its IR pointer`() {
        assertThat(ProductToolkit.explainRule(rev, "no.such.rule")["ok"]).isEqualTo(false)
        val foreign = """{"ruleId":"scheduled.fee","revisionHash":"${"0".repeat(64)}","kind":"POST"}"""
        assertThat(ProductToolkit.explainInstruction(rev, foreign)["message"].toString()).contains("not ${rev.revisionHash}")
        assertThat(ProductToolkit.explainInstruction(rev, "{}")["ok"]).isEqualTo(false)

        val hit = ProductToolkit.explainRule(rev, "scheduled.fee") { "Dsl.kt:7" }
        assertThat(hit["irPath"]).isEqualTo("/hooks/scheduled/2")
        assertThat(hit["source"]).isEqualTo("Dsl.kt:7")
        val own = """{"ruleId":"scheduled.fee","revisionHash":"${rev.revisionHash}","kind":"POST"}"""
        assertThat(ProductToolkit.explainInstruction(rev, own)["irPath"]).isEqualTo("/hooks/scheduled/2")
    }

    @Test
    fun `a proposal plan refuses a non-UUID offering and never publishes`() {
        assertThatThrownBy { ProductToolkit.proposalPlan(rev, "x", emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val plan = ProductToolkit.proposalPlan(
            rev,
            "00000000-0000-0000-0000-0000000000a1",
            mapOf("id" to "deposit", "version" to 1),
        )
        assertThat(plan["publishes"]).isEqualTo(false)
        assertThat(plan["path"]).isEqualTo("/api/v2/offerings/00000000-0000-0000-0000-0000000000a1/revisions")
        assertThat(plan["path"].toString()).doesNotContain("publish")
    }
}
