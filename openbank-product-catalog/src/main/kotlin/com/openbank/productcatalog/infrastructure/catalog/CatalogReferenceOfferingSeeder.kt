// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.observability.DomainMetrics
import com.openbank.productcatalog.application.GenericCatalogService
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.SchemaRef
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0341 adoption: the treasury own-dealing instruments and the CZ DPS / DIP pension offerings
 * exist as catalog offerings, so a contract can pin one of their revisions.
 *
 * Every offering is seeded with an open DRAFT revision under the system maker
 * [SEED_ACTOR]. Publishing stays a separate human step by a different checker (ADR-0257
 * maker-checker, and ADR-0334 dual approval for the pension offerings, whose values are
 * illustrative until legal and commercial review): seeding must never make a revision pinnable on
 * its own. Idempotent — an offering that already exists is left alone, whatever state its
 * revisions are in, so it never competes with a human editing the draft.
 *
 * A suspend `@Scheduled` method, not a startup observer: the repository is reactive, and the
 * scheduler is the supported way to give it a Vert.x context (see `BankV1CompatibilityBackfill`).
 */
@ApplicationScoped
class CatalogReferenceOfferingSeeder(
    private val service: GenericCatalogService,
    private val mapper: ObjectMapper,
    private val catalogJson: CatalogJson,
    private val clock: Clock,
    domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.catalog.reference-offerings-enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.catalog.packs", defaultValue = "banking,insurance,retirement")
    private val enabledPacks: java.util.Optional<String>,
) {
    private val log = Logger.getLogger(CatalogReferenceOfferingSeeder::class.java)
    private val liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    private val complete = AtomicBoolean(false)

    @Scheduled(
        every = "\${openbank.catalog.reference-offering-seed-interval:60s}",
        delayed = "\${openbank.catalog.reference-offering-seed-initial-delay:45s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "catalog-reference-offering-seeder",
    )
    suspend fun seedAfterPacks() {
        if (!enabled || complete.get()) {
            liveness.recordSuccess()
            return
        }
        val packs = enabledPacks.orElse("").split(',').map(String::trim).toSet()
        val seeded = plans(packs).sumOf { seed(it) }
        complete.set(true)
        if (seeded > 0) log.info("Seeded $seeded reference catalog offering(s) as drafts (ADR-0341).")
        liveness.recordSuccess()
    }

    private suspend fun seed(plan: SpecificationPlan): Int {
        val existing = service.listSpecifications().firstOrNull { it.code == plan.code }
        val specification = existing ?: service.createSpecification(
            ProductSpecification(code = plan.code, schemaRef = plan.schema, createdAt = Instant.now(clock)),
            SEED_ACTOR,
        )
        val known = service.listOfferings(specification.id).map { it.code }.toSet()
        return plan.offerings.filter { it.code !in known }.onEach { offering ->
            val created = service.createOffering(specification.id, offering.code, MARKET, SEED_ACTOR)
            service.createDraft(
                offeringId = created.id,
                schemaRef = plan.schema,
                content = RevisionContent(
                    name = LocalizedText(mapOf("en" to offering.name)),
                    attributes = catalogJson.toObject(offering.attributes),
                ),
                effectiveFrom = null,
                effectiveTo = null,
                actorId = SEED_ACTOR,
            )
        }.size
    }

    internal fun plans(packs: Set<String>): List<SpecificationPlan> = buildList {
        if ("banking" in packs) add(treasuryPlan())
        if ("retirement" in packs) addAll(pensionPlans())
    }

    private fun treasuryPlan() = SpecificationPlan(
        code = "TREASURY_INSTRUMENT",
        schema = SchemaRef("org.openbank.banking.treasury-instrument", 1),
        offerings = TREASURY.map { it.toOffering(mapper) },
    )

    private fun pensionPlans(): List<SpecificationPlan> {
        val fixture = requireNotNull(javaClass.getResourceAsStream(PENSION_RESOURCE)) {
            "pension seed resource $PENSION_RESOURCE is missing"
        }.use(mapper::readTree)["offerings"].toList()
        return fixture.groupBy { it["attributes"]["productLine"].asText() }.map { (line, rows) ->
            SpecificationPlan(
                code = "CZ_${line}_PACK",
                schema = SchemaRef("org.openbank.retirement.pension-savings", 3),
                offerings = rows.map { row -> pensionOffering(line, row) },
            )
        }.sortedBy { it.code }
    }

    private fun pensionOffering(line: String, row: JsonNode): OfferingPlan {
        val attributes = (row["attributes"].deepCopy<JsonNode>() as ObjectNode).apply {
            putArray("instrumentClasses").add(if (line == "DPS") "PENSION_FUNDS" else "BOND_FUNDS")
            put("accountingClass", "PENSION_SAVINGS_$line")
            set<ObjectNode>(
                "regulatoryClass",
                mapper.createObjectNode()
                    .put("finrepInstrument", "OFF_BALANCE_DISTRIBUTED_PENSION_PRODUCT")
                    .put("counterpartySector", "HOUSEHOLDS"),
            )
        }
        return OfferingPlan(row["offeringCode"].asText(), row["name"].asText(), attributes)
    }

    internal data class OfferingPlan(val code: String, val name: String, val attributes: JsonNode)

    internal data class SpecificationPlan(val code: String, val schema: SchemaRef, val offerings: List<OfferingPlan>)

    private data class TreasuryInstrument(
        val type: String,
        val name: String,
        val counterpartyKind: String,
        val side: String,
        val currencies: List<String>,
        val accountingClass: String,
        val finrep: String,
        val sector: String,
        val maxTenorDays: Int?,
    ) {
        fun toOffering(mapper: ObjectMapper): OfferingPlan {
            val attributes = mapper.createObjectNode().apply {
                put("instrumentType", type)
                put("counterpartyKind", counterpartyKind)
                put("balanceSide", side)
                putArray("currencies").also { array -> currencies.forEach(array::add) }
                maxTenorDays?.let { put("maxTenorDays", it) }
                put("accountingClass", accountingClass)
                set<ObjectNode>(
                    "regulatoryClass",
                    mapper.createObjectNode().put("finrepInstrument", finrep).put("counterpartySector", sector),
                )
            }
            return OfferingPlan("TREASURY_$type", name, attributes)
        }
    }

    companion object {
        const val SEED_ACTOR = "system:trusted-pack-seeder"
        private const val WORKFLOW_NAME = "catalog-reference-offering-seed"
        private const val PENSION_RESOURCE = "/catalog-seeds/retirement/cz-illustrative-offerings.json"
        private const val OVERNIGHT_DAYS = 1
        private val EXPECTED_INTERVAL: Duration = Duration.ofSeconds(60)
        private val MARKET = MarketContext(countries = setOf("CZ"))
        private val BANK_CURRENCIES = listOf("CZK", "EUR")

        // Dealer-entered prices stay out of the catalog (ADR-0341 D4): classification and limits only.
        private val TREASURY = listOf(
            TreasuryInstrument(
                "MM_PLACEMENT", "Money-market placement with a bank", "BANK", "ASSET", BANK_CURRENCIES,
                "INTERBANK_PLACEMENT", "LOANS_AND_ADVANCES", "CREDIT_INSTITUTIONS", null,
            ),
            TreasuryInstrument(
                "MM_BORROWING", "Money-market borrowing from a bank", "BANK", "LIABILITY", BANK_CURRENCIES,
                "INTERBANK_BORROWING", "BORROWINGS_AT_AMORTISED_COST", "CREDIT_INSTITUTIONS", null,
            ),
            TreasuryInstrument(
                "CNB_DEPOSIT_FACILITY", "Overnight deposit facility at the CNB", "CENTRAL_BANK", "ASSET",
                listOf("CZK"), "CNB_DEPOSIT_FACILITY", "CASH_BALANCES_AT_CENTRAL_BANKS", "CENTRAL_BANK",
                OVERNIGHT_DAYS,
            ),
            TreasuryInstrument(
                "CNB_LOMBARD", "Overnight CNB lombard borrowing", "CENTRAL_BANK", "LIABILITY", listOf("CZK"),
                "CNB_LOMBARD_BORROWING", "BORROWINGS_AT_AMORTISED_COST", "CENTRAL_BANK", OVERNIGHT_DAYS,
            ),
            TreasuryInstrument(
                "FX_SPOT", "Foreign-exchange spot deal", "BANK", "POSITION", BANK_CURRENCIES,
                "FX_SPOT_POSITION", "FX_SPOT_POSITION", "CREDIT_INSTITUTIONS", null,
            ),
        )
    }
}
