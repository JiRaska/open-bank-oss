// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.usecase.ScheduleService
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.ProductLedger
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CollateralTerms
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealCalendars
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.RollDirection
import com.openbank.treasury.domain.model.SecurityTerms
import com.openbank.treasury.domain.reference.BaseCalendar
import com.openbank.treasury.domain.reference.CalendarPayload
import com.openbank.treasury.domain.reference.IndexPayload
import com.openbank.treasury.domain.reference.IndexSource
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.infrastructure.rest.LegSpecDto
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

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
        val deal = deal(fixture["deal"])
        val fixings = fixture["fixings"].associate {
            (it["index"].asText() to LocalDate.parse(it["date"].asText())) to BigDecimal(it["rate"].asText())
        }
        val service = ScheduleService(references(fixture["referenceData"]), NoStoredFlows, clockAt(deal.tradeDate))
        val actual = runBlocking { service.generate(deal, FixingSource.of(fixings)).flows }
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
            diff("rateSource", e.textOrNull("rateSource"), a.rateSource.name)
            amount("amount", e.textOrNull("amount"), a.amount)
        }
        byKey.keys.filter { it !in expectedKeys }.forEach {
            findings +=
                "$id ${label(it)}: the ledger has a flow the fixture does not expect"
        }
        return findings
    }

    private fun label(k: Key) = "leg ${k.leg ?: "deal"} period ${k.period ?: "-"} ${k.kind}"

    // ── the deal, as the service's draft path builds it ──────────────────────────────────────

    private fun deal(d: JsonNode): Deal {
        val product = ProductType.valueOf(d["product"].asText())
        val trade = LocalDate.parse(d["tradeDate"].asText())
        val header = DealHeader(
            calendars = d["calendars"]?.map { it.asText() }.orEmpty(),
            businessDayConvention = d.textOrNull("businessDayConvention"),
            rollDirection = d.textOrNull("rollDirection")?.let(RollDirection::valueOf) ?: RollDirection.FROM_END,
            bookId = d.textOrNull("bookId"),
            securityTerms = d.textOrNull("securityIsin")?.takeIf { product == ProductType.BOND }?.let {
                SecurityTerms(
                    securityIsin = it,
                    nominal = BigDecimal(d["nominal"].asText()),
                    cleanPrice = d.textOrNull("cleanPrice")?.let(::BigDecimal),
                )
            },
            collateral = d["collateral"]?.let {
                CollateralTerms(
                    securityIsin = it["securityIsin"].asText(),
                    nominal = BigDecimal(it["nominal"].asText()),
                    haircutPercent = BigDecimal(it["haircutPercent"].asText()),
                    dirtyPrice = it.textOrNull("dirtyPricePercent")?.let(::BigDecimal),
                )
            },
        )
        if (product == ProductType.FX_SPOT) {
            val (base, _) = d["currencyPair"].asText().split("/")
            return Deal.draft(
                id = UUID.randomUUID(), product = product, counterpartyId = COUNTERPARTY, currency = base,
                principal = BigDecimal(d["baseAmount"].asText()), rate = BigDecimal(d["spotRate"].asText()),
                tradeDate = trade, valueDate = DealCalendars.spotDate(trade, base), maturityDate = null,
                actor = DEALER, at = Instant.EPOCH, fxSide = FxSide.valueOf(d["side"].asText()),
                kind = DealKind.valueOf(d["kind"].asText()), header = header,
            )
        }
        val legs = d["legs"].map { mapper.treeToValue(it, LegSpecDto::class.java).toDomain() }
        val ccy = legs.first().currency
        return Deal.draft(
            id = UUID.randomUUID(),
            product = product,
            counterpartyId = if (product.isCnbFacility) Deal.CNB_COUNTERPARTY_ID else COUNTERPARTY,
            currency = ccy,
            principal = legs.filter { it.currency == ccy }.maxOf { it.notional },
            rate = legs.firstOrNull { it.rate.type == RateType.FIXED }?.rate?.rate ?: BigDecimal.ZERO,
            tradeDate = trade,
            valueDate = d.textOrNull("valueDate")?.takeIf { product == ProductType.BOND }?.let(LocalDate::parse)
                ?: legs.minOf { it.accrualStart },
            maturityDate = legs.maxOf { it.accrualEnd },
            actor = DEALER,
            at = Instant.EPOCH,
            kind = DealKind.valueOf(d["kind"].asText()),
            legs = legs,
            header = header,
        )
    }

    // ── reference data and stores ───────────────────────────────────────────────────────────

    private fun references(ref: JsonNode?): ReferenceRepository {
        val records = BaseCalendar.entries.map {
            record(ReferenceKind.CALENDAR, it.name, CalendarPayload(code = it.name, baseCalendar = it))
        } + ref?.get("indices")?.fields()?.asSequence()?.map { (code, i) ->
            record(
                ReferenceKind.INDEX,
                code,
                IndexPayload(
                    code = code,
                    currency = i["currency"].asText(),
                    tenor = i["tenor"].asText(),
                    dayCount = i["dayCount"].asText(),
                    fixingLagBusinessDays = i["fixingLagBusinessDays"].asInt(),
                    fixingCalendar = i["fixingCalendar"].asText(),
                    source = IndexSource.MANUAL,
                ),
            )
        }?.toList().orEmpty()
        return FixtureReferences(records)
    }

    private fun record(
        kind: ReferenceKind,
        key: String,
        payload: com.openbank.treasury.domain.reference.ReferencePayload,
    ) = ReferenceRecord(
        kind = kind, key = key, version = 1, status = ReferenceStatus.ACTIVE,
        validFrom = LocalDate.of(2000, 1, 1), validTo = null, verified = true, payload = payload,
        createdBy = DEALER, createdAt = Instant.EPOCH, reason = null,
    )

    private class FixtureReferences(private val records: List<ReferenceRecord>) : ReferenceRepository {
        override suspend fun versions(kind: ReferenceKind, key: String) = records.filter {
            it.kind == kind &&
                it.key == key
        }
        override suspend fun list(kind: ReferenceKind, status: ReferenceStatus?, asOf: LocalDate?) = records.filter {
            it.kind ==
                kind
        }
        override suspend fun live() = records
        override suspend fun notEffective(
            refs: Collection<com.openbank.treasury.domain.reference.ReferenceRef>,
            on: LocalDate,
        ) = emptyList<com.openbank.treasury.domain.reference.ReferenceRef>()
        override suspend fun save(
            writes: List<com.openbank.treasury.application.port.out.ReferenceWrite>,
            command: com.openbank.treasury.application.port.out.ReferenceCommandKey?,
        ) = error("the replay stores nothing")
        override suspend fun decide(
            kind: ReferenceKind,
            key: String,
            command: com.openbank.treasury.application.port.out.ReferenceCommandKey,
            decide: (List<ReferenceRecord>) -> List<com.openbank.treasury.application.port.out.ReferenceWrite>,
        ) = error("the replay stores nothing")
        override suspend fun findCommand(key: String) = null
    }

    /** A first generation reads no stored overrides; anything else is a replay bug. */
    private object NoStoredFlows : CashFlowRepository by unsupported()

    companion object {
        private const val MIN_FIXTURES = 36
        private const val COUNTERPARTY = "CPTY-PARITY"
        private val DEALER = Actor("parity-replay", ActorType.HUMAN)
        private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        private fun clockAt(day: LocalDate): Clock =
            Clock.fixed(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

        private fun JsonNode.intOrNull(field: String): Int? = get(field)?.takeUnless { it.isNull }?.asInt()

        fun fixtures(): List<Pair<File, JsonNode>> {
            val dir =
                File(
                    checkNotNull(TreasuryParityReplayTest::class.java.getResource("/parity")) {
                        "no parity fixtures"
                    }.toURI(),
                )
            return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map {
                it to
                    mapper.readTree(it)
            }
        }

        private inline fun <reified T : Any> unsupported(): T = java.lang.reflect.Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, m, _ -> error("the parity replay does not call ${m.name}") } as T
    }
}
