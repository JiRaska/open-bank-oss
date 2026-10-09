// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.decimalString
import com.openbank.customeredge.infrastructure.rest.EdgeJson.int
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * What a customer may send to the pension routes (ADR-0334 S6), validated before anything goes
 * upstream. Each builder returns a body built from scratch — a field the customer sends that is not
 * named here never reaches pension-service, so no request can carry a participant, a current value
 * or an incentive history of the customer's choosing. A failure message names the first bad field.
 */
@Suppress("TooManyFunctions")
internal object PensionInput {
    private val PRODUCT_LINES = setOf("DPS", "DIP")
    private val PROVIDER_TYPES =
        setOf("PENSION_COMPANY", "BANK", "INVESTMENT_FIRM", "MANAGEMENT_COMPANY", "INSURANCE_COMPANY")
    private val FREQUENCIES = setOf("MONTHLY", "QUARTERLY", "ANNUALLY")
    private val PAYOUT_FORMS = setOf("LUMP_SUM", "ANNUITY", "PHASED_WITHDRAWAL")
    private val CODE = Regex("^[A-Z0-9_]{1,64}$")
    private val JURISDICTION = Regex("^[A-Z]{2}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
    private val MAX_AMOUNT = BigDecimal("1000000000")
    private val HUNDRED = BigDecimal("100")
    private const val MAX_NAME = 200
    private const val MAX_BENEFICIARIES = 10
    private const val MAX_HORIZON_YEARS = 60
    private const val MIN_TAX_YEAR = 2000

    fun createContract(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val beneficiaries = node.path("beneficiaries").takeIf { !it.isMissingNode && !it.isNull }
        mapOf(
            "productLine" to oneOf(node, "productLine", PRODUCT_LINES),
            "jurisdiction" to matching(node, "jurisdiction", JURISDICTION),
            "providerEntityId" to uuid(node, "providerEntityId"),
            "providerType" to oneOf(node, "providerType", PROVIDER_TYPES),
            "birthDate" to pastDate(node, "birthDate"),
            "residencyCountry" to node.text("residencyCountry")?.also {
                require(JURISDICTION.matches(it)) { "residencyCountry must be an ISO 3166 alpha-2 code" }
            },
            "schedule" to schedule(node.path("schedule")),
            "strategyCode" to matching(node, "strategyCode", CODE),
            "beneficiaries" to (beneficiaries?.let { beneficiaryList(it) } ?: emptyList<Any>()),
        )
    }

    fun strategy(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val effectiveFrom = node.text("effectiveFrom")?.let { date(it, "effectiveFrom") }
        require(effectiveFrom == null || !effectiveFrom.isBefore(LocalDate.now())) {
            "effectiveFrom must not be in the past"
        }
        mapOf("strategyCode" to matching(node, "strategyCode", CODE), "effectiveFrom" to effectiveFrom?.toString())
    }

    fun schedule(node: JsonNode?): Map<String, Any?> {
        require(node != null && node.isObject) { "schedule is required" }
        val employer = node.decimalString("employerAmount")?.let { amount(it, "schedule.employerAmount") }
        return mapOf(
            "amount" to amount(node.decimalString("amount"), "schedule.amount"),
            "currency" to matching(node, "currency", CURRENCY, "schedule.currency"),
            "frequency" to oneOf(node, "frequency", FREQUENCIES, "schedule.frequency"),
            "employerAmount" to employer,
        )
    }

    fun beneficiaries(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf("beneficiaries" to beneficiaryList(node.path("beneficiaries")))
    }

    fun simulation(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val horizon = requireNotNull(node.int("horizonYears")?.takeIf { it in 1..MAX_HORIZON_YEARS }) {
            "horizonYears must be between 1 and $MAX_HORIZON_YEARS"
        }
        mapOf(
            "productLine" to oneOf(node, "productLine", PRODUCT_LINES),
            "jurisdiction" to matching(node, "jurisdiction", JURISDICTION),
            "strategyCode" to matching(node, "strategyCode", CODE),
            "birthDate" to pastDate(node, "birthDate"),
            "monthlyContribution" to amount(node.decimalString("monthlyContribution"), "monthlyContribution"),
            "employerMonthlyContribution" to node.decimalString("employerMonthlyContribution")
                ?.let { amount(it, "employerMonthlyContribution") },
            "horizonYears" to horizon,
        )
    }

    fun transferIn(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val name = requireNotNull(node.text("cedingProviderName")?.trim()?.takeIf { it.length in 1..MAX_NAME }) {
            "cedingProviderName is required and at most $MAX_NAME characters"
        }
        mapOf(
            "cedingProviderName" to name,
            "cedingContractNumber" to requireNotNull(
                node.text("cedingContractNumber")?.trim()?.takeIf { it.length in 1..MAX_NAME },
            ) { "cedingContractNumber is required" },
            "cedingProviderId" to node.text("cedingProviderId")?.let { uuidOf(it, "cedingProviderId") },
        )
    }

    fun payout(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val iban = requireNotNull(node.text("payoutIban")?.replace(" ", "")?.uppercase()?.takeIf(IBAN::matches)) {
            "payoutIban must be an IBAN"
        }
        mapOf("form" to oneOf(node, "form", PAYOUT_FORMS), "payoutIban" to iban)
    }

    fun taxYear(raw: String?): Int? {
        val year = raw?.toIntOrNull() ?: return null
        return year.takeIf { it in MIN_TAX_YEAR..LocalDate.now().year }
    }

    private fun beneficiaryList(node: JsonNode): List<Map<String, Any?>> {
        require(node.isArray && node.size() <= MAX_BENEFICIARIES) {
            "beneficiaries must be a list of at most $MAX_BENEFICIARIES"
        }
        val list = node.mapIndexed { i, b ->
            val name = requireNotNull(b.text("name")?.trim()?.takeIf { it.length in 1..MAX_NAME }) {
                "beneficiaries[$i].name is required"
            }
            val share = requireNotNull(
                b.decimalString("sharePercent")?.let(::BigDecimal)?.takeIf { it > BigDecimal.ZERO && it <= HUNDRED },
            ) { "beneficiaries[$i].sharePercent must be greater than 0 and at most 100" }
            mapOf(
                "name" to name,
                "partyId" to b.text("partyId")?.let { uuidOf(it, "beneficiaries[$i].partyId") },
                "sharePercent" to share,
            )
        }
        require(list.isEmpty() || list.sumOf { it["sharePercent"] as BigDecimal }.compareTo(HUNDRED) == 0) {
            "beneficiary shares must total 100"
        }
        return list
    }

    private fun oneOf(node: JsonNode, field: String, allowed: Set<String>, label: String = field): String =
        requireNotNull(node.text(field)?.takeIf { it in allowed }) { "$label must be one of ${allowed.joinToString()}" }

    private fun matching(node: JsonNode, field: String, pattern: Regex, label: String = field): String =
        requireNotNull(node.text(field)?.takeIf(pattern::matches)) { "$label is missing or malformed" }

    private fun uuid(node: JsonNode, field: String): String =
        uuidOf(requireNotNull(node.text(field)) { "$field is required" }, field)

    private fun uuidOf(raw: String, field: String): String =
        requireNotNull(runCatching { UUID.fromString(raw) }.getOrNull()) { "$field must be a UUID" }.toString()

    private fun date(raw: String, field: String): LocalDate =
        requireNotNull(runCatching { LocalDate.parse(raw) }.getOrNull()) { "$field must be an ISO date" }

    private fun pastDate(node: JsonNode, field: String): String {
        val value = date(requireNotNull(node.text(field)) { "$field is required" }, field)
        require(value.isBefore(LocalDate.now())) { "$field must be in the past" }
        return value.toString()
    }

    private fun amount(raw: String?, field: String): BigDecimal =
        requireNotNull(raw?.let(::BigDecimal)?.takeIf { it >= BigDecimal.ZERO && it <= MAX_AMOUNT }) {
            "$field must be a non-negative amount"
        }
}

/**
 * The customer contract for a pension contract. `participantPartyId` is the caller and is dropped;
 * the pinned pack version stays because it decides which rules the customer's contract runs under.
 */
internal object PensionProjection {
    fun contract(c: JsonNode): Map<String, Any?> = mapOf(
        "contractId" to c.text("contractId"),
        "productLine" to c.text("productLine"),
        "jurisdiction" to c.text("jurisdiction"),
        "packVersion" to c.int("packVersion"),
        "providerType" to c.text("providerType"),
        "status" to c.text("status"),
        "schedule" to c.path("schedule").takeIf { it.isObject }?.let(::schedule),
        "currentStrategy" to c.path("currentStrategy").takeIf { it.isObject }?.let(::strategy),
        "strategyHistory" to c.path("strategyHistory").filter { it.isObject }.map(::strategy),
        "beneficiaries" to c.path("beneficiaries").filter { it.isObject }.map {
            mapOf("name" to it.text("name"), "sharePercent" to it.decimalString("sharePercent"))
        },
        "startDate" to c.text("startDate"),
        "createdAt" to c.text("createdAt"),
        "updatedAt" to c.text("updatedAt"),
    )

    /** Holdings at the latest published NAV, summed per currency. A holding with no NAV yet is unvalued. */
    fun valuation(v: JsonNode): Map<String, Any?> {
        val holdings = v.path("holdings").filter { it.isObject }.map {
            mapOf(
                "fundId" to it.text("fundId"),
                "units" to it.decimalString("units"),
                "navPerUnit" to it.decimalString("navPerUnit"),
                "navDate" to it.text("navDate"),
                "value" to it.decimalString("value"),
                "currency" to it.text("currency"),
            )
        }
        val totals = holdings.filter { it["value"] != null && it["currency"] != null }
            .groupBy { it["currency"] as String }
            .mapValues { (_, rows) ->
                rows.sumOf { BigDecimal(it["value"] as String) }.stripTrailingZeros().toPlainString()
            }
        return mapOf(
            "holdings" to holdings,
            "totals" to totals,
            "pendingOrders" to v.path("pendingOrders").size(),
            "complete" to holdings.all { it["value"] != null },
        )
    }

    fun transaction(t: JsonNode): Map<String, Any?> = mapOf(
        "type" to t.text("type"),
        "fundId" to t.text("fundId"),
        "units" to t.decimalString("units"),
        "amount" to t.decimalString("amount"),
        "navPerUnit" to t.decimalString("navPerUnit"),
        "pricedAt" to t.text("pricedAt"),
    )

    fun earlyTermination(e: JsonNode): Map<String, Any?> = mapOf(
        "payoutConditionsMet" to e.path("payoutConditionsMet").asBoolean(false),
        "earlyWithdrawalAllowed" to e.path("earlyWithdrawalAllowed").asBoolean(false),
        "ageAtExit" to e.int("ageAtExit"),
        "durationMonths" to e.int("durationMonths"),
        "currentValue" to e.decimalString("currentValue"),
        "fee" to e.decimalString("fee"),
        "clawbacks" to e.path("clawbacks").filter { it.isObject }.map {
            mapOf(
                "incentiveId" to it.text("incentiveId"),
                "mode" to it.text("mode"),
                "amount" to it.decimalString("amount"),
            )
        },
        "estimatedNetPayout" to e.decimalString("estimatedNetPayout"),
        "notes" to e.path("notes").filter { it.isTextual }.map { it.textValue() },
        "status" to e.path("contract").text("status"),
    )

    /** One published retirement offering, from its catalog projection. Null when it is not a pension product. */
    fun product(offeringId: String, revision: JsonNode): Map<String, Any?>? {
        val attributes = revision.path("content").path("attributes")
        val line = attributes.text("productLine")?.takeIf { it == "DPS" || it == "DIP" } ?: return null
        return mapOf(
            "offeringId" to offeringId,
            "name" to revision.path("content").text("name"),
            "productLine" to line,
            "jurisdictionPackId" to attributes.text("jurisdictionPackId"),
            "fundStrategy" to attributes.text("fundStrategy"),
            "currency" to attributes.text("currency"),
            "riskClass" to attributes.int("riskClass"),
            "permittedProviderTypes" to attributes.path("permittedProviderTypes").map { it.asText() },
            "feeSchedule" to objectOf(attributes.path("feeSchedule")),
            "contributionLimits" to objectOf(attributes.path("contributionLimits")),
            "sfdrArticle" to attributes.path("sustainability").text("sfdrArticle"),
            "requiredDocuments" to attributes.path("requiredDocuments").map { it.asText() },
            "reviewStatus" to attributes.text("reviewStatus"),
        )
    }

    private fun objectOf(node: JsonNode): Map<*, *>? =
        node.takeIf { it.isObject }?.let { EdgeJson.mapper.convertValue(it, Map::class.java) }

    private fun schedule(s: JsonNode): Map<String, Any?> = mapOf(
        "amount" to s.decimalString("amount"),
        "currency" to s.text("currency"),
        "frequency" to s.text("frequency"),
        "employerAmount" to s.decimalString("employerAmount"),
    )

    private fun strategy(s: JsonNode): Map<String, Any?> = mapOf(
        "strategyCode" to s.text("strategyCode"),
        "effectiveFrom" to s.text("effectiveFrom"),
        "electedAt" to s.text("electedAt"),
    )
}
