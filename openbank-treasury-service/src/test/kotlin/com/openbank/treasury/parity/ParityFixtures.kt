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
import com.openbank.treasury.infrastructure.LegWireJackson
import com.openbank.treasury.infrastructure.rest.LegSpecDto
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The parity fixtures and the service-side objects a fixture builds: its deal, references, fixings. */
object ParityFixtures {
    private const val COUNTERPARTY = "CPTY-PARITY"
    private val DEALER = Actor("parity-replay", ActorType.HUMAN)
    private val mapper = LegWireJackson.register(jacksonObjectMapper().registerModule(JavaTimeModule()))

    fun fixtures(): List<Pair<File, JsonNode>> {
        val dir = File(checkNotNull(ParityFixtures::class.java.getResource("/parity")) { "no parity fixtures" }.toURI())
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { it to mapper.readTree(it) }
    }

    fun fixings(fixture: JsonNode): FixingSource = FixingSource.of(
        fixture["fixings"].associate {
            (it["index"].asText() to LocalDate.parse(it["date"].asText())) to BigDecimal(it["rate"].asText())
        },
    )

    fun scheduleService(fixture: JsonNode, deal: Deal): ScheduleService =
        ScheduleService(references(fixture["referenceData"]), NoStoredFlows, clockAt(deal.tradeDate))

    private fun clockAt(day: LocalDate): Clock =
        Clock.fixed(day.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    // ── the deal, as the service's draft path builds it ──────────────────────────────────────

    fun deal(d: JsonNode): Deal {
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

    fun references(ref: JsonNode?): ReferenceRepository {
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

    private inline fun <reified T : Any> unsupported(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, m, _ -> error("the parity replay does not call ${m.name}") } as T
}
