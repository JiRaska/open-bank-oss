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
 * named here never reaches pension-service, so no request can carry a participant, a birth date or
 * a residency of the customer's choosing. A failure message names the first bad field.
 */
@Suppress("TooManyFunctions")
internal object PensionInput {
    private val PRODUCT_LINES = setOf("DPS", "DIP")
    private val PROVIDER_TYPES =
        setOf("PENSION_COMPANY", "BANK", "INVESTMENT_FIRM", "MANAGEMENT_COMPANY", "INSURANCE_COMPANY")
    private val FREQUENCIES = setOf("MONTHLY", "QUARTERLY", "ANNUALLY")
    private val PAYOUT_FORMS = setOf("LUMP_SUM", "ANNUITY", "PHASED_WITHDRAWAL", "FIXED_PERIOD_PENSION")
    private const val MAX_PAYOUT_MONTHS = 600
    private val CODE = Regex("^[A-Z0-9_]{1,64}$")
    private val JURISDICTION = Regex("^[A-Z]{2}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
    private val MAX_AMOUNT = BigDecimal("1000000000")
    private val LANGUAGE = Regex("^[a-z]{2}$")
    private val ESG = setOf("NONE", "CONSIDER", "REQUIRED")
    private val MANDATE_KINDS = setOf("STANDING_ORDER", "DIRECT_DEBIT")
    private const val MAX_NAME = 200
    private const val MAX_LEVEL = 3
    private const val MAX_HORIZON_YEARS = 60
    private const val MIN_TAX_YEAR = 2000

    /**
     * The customer's part of an onboarding application (S2). Birth date and residency are NOT taken
     * from the customer: the edge reads them from the party record (see [CustomerPensionResource]),
     * so eligibility runs on the facts KYC holds, never on what the app declares.
     */
    fun application(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf(
            "productLine" to oneOf(node, "productLine", PRODUCT_LINES),
            "jurisdiction" to matching(node, "jurisdiction", JURISDICTION),
            "providerEntityId" to uuid(node, "providerEntityId"),
            "providerType" to oneOf(node, "providerType", PROVIDER_TYPES),
            "schedule" to schedule(node.path("schedule")),
        )
    }

    /** An application of kind TRANSFER_IN: the application fields plus the ceding contract. */
    fun transferIn(node: JsonNode?): Result<Map<String, Any?>> = application(node).mapCatching { base ->
        val ceding = node!!.path("transferIn")
        require(ceding.isObject) { "transferIn is required" }
        base + (
            "transferIn" to mapOf(
                "providerId" to bounded(ceding, "providerId", "transferIn.providerId"),
                "providerName" to bounded(ceding, "providerName", "transferIn.providerName"),
                "contractNumber" to bounded(ceding, "contractNumber", "transferIn.contractNumber"),
            )
            )
    }

    /**
     * The questionnaire (F7, pension-service 1.2.0): `answers` keyed by question id from the
     * question set, plus the contradictions the participant confirmed and the language shown. The
     * pre-F7 level fields are still accepted when no `answers` are sent.
     */
    fun questionnaire(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val language = node.text("language")?.also { require(LANGUAGE.matches(it)) { "language is malformed" } }
        if (node.has("answers")) {
            val confirm = node.path("confirmInconsistencies")
            require(confirm.isMissingNode || confirm.isNull || confirm.isArray) {
                "confirmInconsistencies must be a list"
            }
            return@runCatching mapOf(
                "answers" to PensionChangeInput.answers(node),
                "confirmInconsistencies" to confirm.takeIf { it.isArray }?.map { c ->
                    requireNotNull(c.takeIf { it.isTextual }?.textValue()?.takeIf(CODE::matches)) {
                        "confirmInconsistencies must list inconsistency codes"
                    }
                },
                "language" to language,
            )
        }
        val stable = node.path("financialSituationStable")
        require(stable.isBoolean) { "financialSituationStable must be true or false" }
        mapOf(
            "knowledgeLevel" to level(node, "knowledgeLevel", required = false),
            "experienceLevel" to level(node, "experienceLevel", required = false),
            "riskAppetite" to level(node, "riskAppetite", required = true),
            "lossTolerance" to level(node, "lossTolerance", required = true),
            "financialSituationStable" to stable.booleanValue(),
            "esgPreference" to node.text("esgPreference")?.also {
                require(it in ESG) { "esgPreference must be one of ${ESG.joinToString()}" }
            },
            "language" to language,
        )
    }

    /** Strategy choice; an omitted strategyCode accepts the recommendation. */
    fun chooseStrategy(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val acknowledge = node.path("acknowledgeWarning")
        require(acknowledge.isMissingNode || acknowledge.isNull || acknowledge.isBoolean) {
            "acknowledgeWarning must be true or false"
        }
        mapOf(
            "strategyCode" to
                node.text("strategyCode")?.also { require(CODE.matches(it)) { "strategyCode is malformed" } },
            "acknowledgeWarning" to acknowledge.takeIf { it.isBoolean }?.booleanValue(),
            "language" to node.text("language")?.also { require(LANGUAGE.matches(it)) { "language is malformed" } },
        )
    }

    fun kidAcceptance(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf("documentId" to bounded(node, "documentId", "documentId"))
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

    /** A contribution payment mandate (S3 funding route): standing order or direct debit. */
    fun mandate(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val first =
            date(requireNotNull(node.text("firstCollection")) { "firstCollection is required" }, "firstCollection")
        require(!first.isBefore(LocalDate.now())) { "firstCollection must not be in the past" }
        val amount = amount(node.decimalString("amount"), "amount")
        require(amount > BigDecimal.ZERO && amount.scale() <= 2) { "amount must be positive with at most two decimals" }
        mapOf(
            "kind" to oneOf(node, "kind", MANDATE_KINDS),
            "debtorIban" to iban(node, "debtorIban"),
            "amount" to amount,
            "currency" to matching(node, "currency", CURRENCY),
            "firstCollection" to first.toString(),
            "debtorName" to node.text("debtorName")?.trim()?.also {
                require(it.length in 1..MAX_NAME) { "debtorName is at most $MAX_NAME characters" }
            },
        )
    }

    /** The S8 simulation request; the projection is illustrative and binds nothing. */
    fun simulation(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val horizon = requireNotNull(node.int("horizonYears")?.takeIf { it in 1..MAX_HORIZON_YEARS }) {
            "horizonYears must be between 1 and $MAX_HORIZON_YEARS"
        }
        val monthly = amount(node.decimalString("monthlyContribution"), "monthlyContribution")
        require(monthly > BigDecimal.ZERO) { "monthlyContribution must be positive" }
        mapOf(
            "productLine" to oneOf(node, "productLine", PRODUCT_LINES),
            "jurisdiction" to matching(node, "jurisdiction", JURISDICTION),
            "strategyCode" to
                node.text("strategyCode")?.also { require(CODE.matches(it)) { "strategyCode is malformed" } },
            "monthlyContribution" to monthly,
            "employerMonthlyContribution" to node.decimalString("employerMonthlyContribution")
                ?.let { amount(it, "employerMonthlyContribution") },
            "horizonYears" to horizon,
        )
    }

    /** A payout quote. Early withdrawal and surrender go through the early-termination routes instead. */
    fun payoutQuote(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val months = node.path("months").takeIf { !it.isMissingNode && !it.isNull }?.let {
            requireNotNull(
                it.takeIf { m ->
                    m.isIntegralNumber
                }?.intValue()?.takeIf { m -> m in 1..MAX_PAYOUT_MONTHS },
            ) {
                "months must be between 1 and $MAX_PAYOUT_MONTHS"
            }
        }
        mapOf(
            "form" to oneOf(node, "form", PAYOUT_FORMS),
            "amount" to node.decimalString("amount")?.let { amount(it, "amount") },
            "months" to months,
        )
    }

    /**
     * The account a signed exit pays to: termination sign, payout confirm and payout account change
     * all take only `payoutIban`. The SCA challenge travels in `X-SCA-Challenge-Id`.
     */
    fun payoutAccount(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf("payoutIban" to iban(node, "payoutIban"))
    }

    fun taxYear(raw: String?): Int? {
        val year = raw?.toIntOrNull() ?: return null
        return year.takeIf { it in MIN_TAX_YEAR..LocalDate.now().year }
    }

    private fun level(node: JsonNode, field: String, required: Boolean): Int? {
        val value = node.path(field)
        if (!required && (value.isMissingNode || value.isNull)) return null
        return requireNotNull(value.takeIf { it.isIntegralNumber }?.intValue()?.takeIf { it in 0..MAX_LEVEL }) {
            "$field must be an integer from 0 to $MAX_LEVEL"
        }
    }

    private fun bounded(node: JsonNode, field: String, label: String): String =
        requireNotNull(node.text(field)?.trim()?.takeIf { it.length in 1..MAX_NAME }) {
            "$label is required and at most $MAX_NAME characters"
        }

    private fun iban(node: JsonNode, field: String): String =
        requireNotNull(node.text(field)?.replace(" ", "")?.uppercase()?.takeIf(IBAN::matches)) {
            "$field must be an IBAN"
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

/**
 * Dynamic linking of a pension operation for sca-service's APPROVAL challenge: the operation and
 * contract as `approvalRequestId`, and SHA-256 over a canonical (key-sorted) JSON of the operation,
 * contract and exact payload as `payloadSha256`. Deterministic, so the edge computes the same value
 * when it hands the linking to the app and when it consumes the challenge.
 */
internal object PensionScaLinking {
    private val canonical = EdgeJson.mapper.copy()
        .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun of(operation: String, contractId: UUID, payload: Any): Map<String, String> {
        val document = mapOf("operation" to operation, "contractId" to contractId.toString(), "payload" to payload)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(document))
        return mapOf(
            "approvalRequestId" to "pension.$operation:$contractId",
            "payloadSha256" to digest.joinToString("") { "%02x".format(it) },
        )
    }
}
