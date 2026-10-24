// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.treasury.application.usecase.ScheduleService
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.ProductLedger
import com.openbank.treasury.domain.cashflow.RateSourceWire
import com.openbank.treasury.domain.model.ProductType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.math.BigDecimal

/**
 * ADR-0344 D9: replays every golden fixture under `src/test/resources/parity` through the SAME
 * domain path the service uses — the deal aggregate, [ScheduleService] with the fixture's
 * reference data and observed fixings, the shared schedule engine, and the product-specific ledger
 * shape ([ProductLedger]: FRA settlement, bond purchase) — and compares every expected flow field
 * by field: dates exactly, amounts and notionals within the fixture's tolerance, rates exactly.
 *
 * A difference is a NAMED FINDING per flow (fixture, leg, period, kind, field, both values), all of
 * them reported together; one fixture is one dynamic test. The fixture format is documented in
 * `parity/README.md`; the only wire mappings applied here are the ones it lists (a deal-level flow's
 * null `legIndex`/`periodIndex` is the ledger's pseudo-leg -1).
 */
class TreasuryParityReplayTest {
    @TestFactory
    fun `every parity fixture replays through the service's own ledger path`(): List<DynamicTest> =
        fixtures().map { (file, node) ->
            DynamicTest.dynamicTest(node["id"].asText()) {
                val findings = replay(node)
                assertThat(findings)
                    .withFailMessage { "${file.name}: ${findings.size} finding(s)\n" + findings.joinToString("\n") }
                    .isEmpty()
            }
        }

    @Test
    fun `the fixture set is the one the gate counts - every product has one and none is skipped`() {
        val all = fixtures()
        assertThat(all).hasSizeGreaterThanOrEqualTo(MIN_FIXTURES)
        assertThat(all.map { ProductType.valueOf(it.second["product"].asText()) }.toSet())
            .containsExactlyInAnyOrderElementsOf(ProductType.entries)
    }

    @Test
    fun `a wrong expected value is reported as a named finding, not swallowed`() {
        val (_, node) = fixtures().first { it.second["id"].asText() == "loan-czk-annuity-002" }
        val tampered = node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        val flow = tampered["expected"]["flows"][1] as com.fasterxml.jackson.databind.node.ObjectNode
        flow.put("amount", "1.00")
        flow.put("paymentDate", "2000-01-03")
        val findings = replay(tampered)
        assertThat(findings).hasSize(2)
        assertThat(findings).allMatch { it.startsWith("loan-czk-annuity-002 leg 0 period 0 INTEREST:") }
    }

    // ── replay ──────────────────────────────────────────────────────────────────────────────

    private fun replay(fixture: JsonNode): List<String> {
        val id = fixture["id"].asText()
        val deal = ParityFixtures.deal(fixture["deal"])
        val service = ParityFixtures.scheduleService(fixture, deal)
        val actual = runBlocking { service.generate(deal, ParityFixtures.fixings(fixture)).flows }
        val tolerance = BigDecimal(fixture["tolerance"]["amount"].asText())
        return compare(id, fixture["expected"]["flows"].toList(), actual, tolerance)
    }

    private data class Key(val leg: Int?, val period: Int?, val kind: String)

    private fun keyOf(f: CashFlow): Key {
        val dealLevel = f.legIndex == ProductLedger.DEAL_LEVEL_LEG
        return Key(
            if (dealLevel) null else f.legIndex,
            if (dealLevel && f.periodIndex == -1) null else f.periodIndex,
            f.kind.name,
        )
    }

    private fun keyOf(n: JsonNode) = Key(n.intOrNull("legIndex"), n.intOrNull("periodIndex"), n["kind"].asText())

    @Suppress("CyclomaticComplexMethod")
    private fun compare(id: String, expected: List<JsonNode>, actual: List<CashFlow>, tol: BigDecimal): List<String> {
        val findings = mutableListOf<String>()
        val byKey = actual.groupBy(::keyOf)
        byKey.filterValues { it.size > 1 }.keys.forEach {
            findings +=
                "$id ${label(it)}: the ledger has ${byKey[it]!!.size} flows on one slot"
        }
        val expectedKeys = expected.map(::keyOf)
        expected.forEach { e ->
            val k = keyOf(e)
            val a = byKey[k]?.firstOrNull()
            if (a == null) {
                findings += "$id ${label(k)}: expected flow is missing from the ledger"
                return@forEach
            }
            fun diff(field: String, want: Any?, got: Any?) {
                if (want != got) findings += "$id ${label(k)}: $field expected $want, actual $got"
            }
            fun amount(field: String, want: String?, got: BigDecimal?) {
                val ok = when {
                    want == null || got == null -> want == null && got == null
                    else -> (BigDecimal(want) - got).abs() <= tol
                }
                if (!ok) findings += "$id ${label(k)}: $field expected $want, actual ${got?.toPlainString()}"
            }
            e.textOrNull("direction")?.let { diff("direction", it, a.direction.name) }
            diff("currency", e.textOrNull("currency"), a.currency)
            diff("accrualStart", e.textOrNull("accrualStart"), a.accrualStart?.toString())
            diff("accrualEnd", e.textOrNull("accrualEnd"), a.accrualEnd?.toString())
            diff("fixingDate", e.textOrNull("fixingDate"), a.fixingDate?.toString())
            diff("paymentDate", e.textOrNull("paymentDate"), a.paymentDate.toString())
            amount("notional", e.textOrNull("notional"), a.notional)
            val rateOk = e.textOrNull("rate").let { w ->
                if (w == null) a.rate == null else a.rate != null && BigDecimal(w).compareTo(a.rate) == 0
            }
            if (!rateOk) {
                findings +=
                    "$id ${label(k)}: rate expected ${e.textOrNull("rate")}, actual ${a.rate?.toPlainString()}"
            }
            diff("rateSource", e.textOrNull("rateSource"), RateSourceWire.encode(a.rateSource))
            amount("amount", e.textOrNull("amount"), a.amount)
        }
        byKey.keys.filter { it !in expectedKeys }.forEach {
            findings +=
                "$id ${label(it)}: the ledger has a flow the fixture does not expect"
        }
        return findings
    }

    private fun label(k: Key) = "leg ${k.leg ?: "deal"} period ${k.period ?: "-"} ${k.kind}"

    companion object {
        private const val MIN_FIXTURES = 36

        private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

        private fun JsonNode.intOrNull(field: String): Int? = get(field)?.takeUnless { it.isNull }?.asInt()

        fun fixtures(): List<Pair<File, JsonNode>> = ParityFixtures.fixtures()
    }
}
