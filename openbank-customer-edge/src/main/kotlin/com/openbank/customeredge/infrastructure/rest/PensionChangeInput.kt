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
 * Request bodies of the pension follow-up flows (pension-service API 1.2.0): the questionnaire
 * draft and warning acknowledgement (F7), schedule and beneficiary changes (F1), annuity offers and
 * selection (F6). Like [PensionInput], each body is rebuilt from named fields, so nothing the app
 * adds reaches pension-service, and a challenge id is never read from the body — it travels in
 * `X-SCA-Challenge-Id` and is set by the edge.
 */
@Suppress("TooManyFunctions")
internal object PensionChangeInput {
    private val ANSWER_KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val ANSWER_VALUE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val CODE = Regex("^[A-Z0-9_]{1,64}$")
    private val LANGUAGE = Regex("^[a-z]{2}$")
    private val WARNINGS =
        setOf("STRATEGY_ABOVE_PROFILE", "PRODUCT_NOT_APPROPRIATE", "SUSTAINABILITY_PREFERENCE_NOT_MET")
    private val FREQUENCIES = setOf("MONTHLY", "QUARTERLY", "ANNUALLY")
    private val ANNUITY_TYPES = setOf("LIFELONG", "FIXED_TERM", "GUARANTEE_PERIOD", "JOINT_LIFE", "INDEXED")
    private val OFFER_REF = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    private val MAX_AMOUNT = BigDecimal("1000000000")
    private val HUNDRED = BigDecimal("100")
    private const val MAX_ANSWERS = 100
    private const val MAX_BENEFICIARIES = 10
    private const val MAX_NAME = 200
    private const val MAX_DAY = 28
    private const val MAX_MONTHS = 600

    /** `answers`: question id -> option code, closed answers only (no free text is ever scored). */
    fun answers(node: JsonNode?): Map<String, String> {
        val answers = node?.path("answers")
        require(answers != null && answers.isObject) { "answers must be an object" }
        require(answers.size() <= MAX_ANSWERS) { "too many answers" }
        return answers.fields().asSequence().associate { (key, value) ->
            require(ANSWER_KEY.matches(key)) { "answer key $key is malformed" }
            val option = value.takeIf { it.isTextual }?.textValue()
            require(option != null && ANSWER_VALUE.matches(option)) { "answer $key must be an option code" }
            key to option
        }
    }

    fun draft(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf("answers" to answers(node))
    }

    fun acknowledge(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf(
            "strategyCode" to code(node, "strategyCode"),
            "warnings" to warnings(node, "warnings").also { require(it.isNotEmpty()) { "warnings is required" } },
            "language" to language(node),
        )
    }

    /** A strategy change of an active contract; [today] fills an omitted effective date. */
    fun strategy(node: JsonNode?, today: LocalDate): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val from = node.text("effectiveFrom")?.let { date(it, "effectiveFrom") } ?: today
        require(!from.isBefore(today)) { "effectiveFrom must not be in the past" }
        mapOf(
            "strategyCode" to code(node, "strategyCode"),
            "effectiveFrom" to from.toString(),
            "acknowledgedWarnings" to warnings(node, "acknowledgedWarnings"),
            "language" to language(node),
        )
    }

    fun scheduleChange(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val amount = requireNotNull(node.decimalString("amount")?.let(::BigDecimal)) { "amount is required" }
        require(amount >= BigDecimal.ZERO && amount <= MAX_AMOUNT && amount.scale() <= 2) {
            "amount must be a non-negative amount with at most two decimals"
        }
        val frequency = requireNotNull(node.text("frequency")?.takeIf { it in FREQUENCIES }) {
            "frequency must be one of ${FREQUENCIES.joinToString()}"
        }
        val day = requireNotNull(node.int("dayOfMonth")?.takeIf { it in 1..MAX_DAY }) {
            "dayOfMonth must be from 1 to $MAX_DAY"
        }
        val start = node.text("startDate")?.let { date(it, "startDate") }
        val acknowledge = node.path("acknowledgeIncentiveReduction")
        require(acknowledge.isMissingNode || acknowledge.isNull || acknowledge.isBoolean) {
            "acknowledgeIncentiveReduction must be true or false"
        }
        mapOf(
            "amount" to amount,
            "frequency" to frequency,
            "dayOfMonth" to day,
            "startDate" to start?.toString(),
            "acknowledgeIncentiveReduction" to (acknowledge.takeIf { it.isBoolean }?.booleanValue() ?: false),
        )
    }

    fun beneficiaryChange(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val list = node.path("beneficiaries")
        require(list.isArray && list.size() in 1..MAX_BENEFICIARIES) {
            "beneficiaries must list 1 to $MAX_BENEFICIARIES people"
        }
        val beneficiaries = list.map { b ->
            require(b.isObject) { "each beneficiary must be an object" }
            val name = requireNotNull(b.text("name")?.trim()?.takeIf { it.length in 1..MAX_NAME }) {
                "beneficiary name is required and at most $MAX_NAME characters"
            }
            val share = requireNotNull(b.decimalString("sharePercent")?.let(::BigDecimal)) {
                "beneficiary sharePercent is required"
            }
            require(share > BigDecimal.ZERO && share <= HUNDRED) { "sharePercent must be above 0 and at most 100" }
            mapOf(
                "name" to name,
                "partyId" to b.text("partyId")?.let { uuid(it, "beneficiary partyId") },
                "sharePercent" to share,
            )
        }
        require(beneficiaries.sumOf { it["sharePercent"] as BigDecimal }.compareTo(HUNDRED) == 0) {
            "shares must total exactly 100"
        }
        mapOf("beneficiaries" to beneficiaries)
    }

    fun annuityOffers(node: JsonNode?): Result<Map<String, Any?>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        val types = node.path("annuityTypes").takeIf { !it.isMissingNode && !it.isNull }?.map { t ->
            requireNotNull(t.takeIf { it.isTextual }?.textValue()?.takeIf { it in ANNUITY_TYPES }) {
                "annuityTypes must be drawn from ${ANNUITY_TYPES.joinToString()}"
            }
        }
        val survivor = node.decimalString("survivorShare")?.let(::BigDecimal)
        require(survivor == null || (survivor >= BigDecimal.ZERO && survivor <= BigDecimal.ONE)) {
            "survivorShare must be between 0 and 1"
        }
        mapOf(
            "annuityTypes" to types,
            "guaranteeMonths" to months(node, "guaranteeMonths", 0),
            "termMonths" to months(node, "termMonths", 1),
            "jointLifeBirthDate" to node.text("jointLifeBirthDate")?.let { date(it, "jointLifeBirthDate").toString() },
            "survivorShare" to survivor,
        )
    }

    fun annuitySelection(node: JsonNode?): Result<Map<String, String>> = runCatching {
        requireNotNull(node) { "body must be a JSON object" }
        mapOf(
            "partnerId" to
                requireNotNull(node.text("partnerId")?.takeIf(OFFER_REF::matches)) { "partnerId is required" },
            "offerId" to requireNotNull(node.text("offerId")?.takeIf(OFFER_REF::matches)) { "offerId is required" },
        )
    }

    private fun months(node: JsonNode, field: String, min: Int): Int? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        return requireNotNull(value.takeIf { it.isIntegralNumber }?.intValue()?.takeIf { it in min..MAX_MONTHS }) {
            "$field must be an integer from $min to $MAX_MONTHS"
        }
    }

    private fun warnings(node: JsonNode, field: String): List<String> {
        val list = node.path(field)
        if (list.isMissingNode || list.isNull) return emptyList()
        require(list.isArray) { "$field must be a list" }
        return list.map { w ->
            requireNotNull(w.takeIf { it.isTextual }?.textValue()?.takeIf { it in WARNINGS }) {
                "$field must be drawn from ${WARNINGS.joinToString()}"
            }
        }.distinct()
    }

    private fun code(node: JsonNode, field: String): String =
        requireNotNull(node.text(field)?.takeIf(CODE::matches)) { "$field is missing or malformed" }

    private fun language(node: JsonNode): String? =
        node.text("language")?.also { require(LANGUAGE.matches(it)) { "language is malformed" } }

    private fun uuid(raw: String, field: String): String =
        requireNotNull(runCatching { UUID.fromString(raw) }.getOrNull()) { "$field must be a UUID" }.toString()

    private fun date(raw: String, field: String): LocalDate =
        requireNotNull(runCatching { LocalDate.parse(raw) }.getOrNull()) { "$field must be an ISO date" }

    /** `lang` query parameter: cs or en only. */
    fun lang(raw: String?): String? = raw?.takeIf { it == "cs" || it == "en" }
}
