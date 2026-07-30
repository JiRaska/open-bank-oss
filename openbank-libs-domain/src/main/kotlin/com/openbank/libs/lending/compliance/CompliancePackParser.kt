// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyOperator
import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.lending.origination.OriginationState
import java.math.BigDecimal
import java.time.LocalDate

/** Strict pack decoding failed — the pack is rejected whole, never coerced (ADR-0212 D2). */
class CompliancePackParseException(message: String) : IllegalArgumentException(message)

/**
 * Strict, fail-closed decoder for compliance packs. The schema is closed: an unknown
 * key, an unknown enum value, a wrong type or a missing mandatory field rejects the
 * whole pack — the bank never originates under a rule set it could not fully read.
 * Format-agnostic: [fromMap] does the decoding; [fromJson] is the JSON convenience
 * (a YAML front-end needs no change here, only a YAML→Map step at the caller).
 */
object CompliancePackParser {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    fun fromJson(json: String): CompliancePack = fromMap(mapper.readValue(json))

    fun fromMap(raw: Map<String, Any?>): CompliancePack {
        val node = Node(raw, "pack")
        node.require(MANDATORY_KEYS)
        node.rejectUnknown(ALL_KEYS)
        return CompliancePack(
            jurisdiction = node.textOf("jurisdiction"),
            productType = node.enumValue("productType", PackProductType.entries),
            version = node.int("version"),
            effectiveFrom = node.date("effectiveFrom"),
            effectiveTo = node.optDate("effectiveTo"),
            requiredSteps = node.strList("requiredSteps").map { step ->
                OriginationState.entries.firstOrNull { it.name == step }
                    ?: throw CompliancePackParseException("requiredSteps: unknown origination state '$step'")
            }.toSet(),
            coolingOffDays = node.int("coolingOffDays"),
            reflectionPeriodDays = node.optInt("reflectionPeriodDays"),
            aprDisclosure = aprDisclosure(node),
            earlyRepaymentCompensationCap = node.optDecimal("earlyRepaymentCompensationCap"),
            terminationRules = terminationRules(node),
            disclosures = node.objList("disclosures").mapIndexed { i, d -> disclosure(Node(d, "disclosures[$i]")) },
            mandatoryChecks = node.objList("mandatoryChecks").mapIndexed { i, r ->
                policyRule(Node(r, "mandatoryChecks[$i]"))
            },
        )
    }

    private val MANDATORY_KEYS = setOf(
        "jurisdiction",
        "productType",
        "version",
        "effectiveFrom",
        "coolingOffDays",
        "aprDisclosure",
        "terminationRules",
    )

    private val ALL_KEYS = MANDATORY_KEYS + setOf(
        "effectiveTo",
        "requiredSteps",
        "reflectionPeriodDays",
        "earlyRepaymentCompensationCap",
        "disclosures",
        "mandatoryChecks",
    )

    private fun aprDisclosure(parent: Node): AprDisclosure {
        val node = parent.obj("aprDisclosure")
        node.rejectUnknown(setOf("label", "locale"))
        node.require(setOf("label", "locale"))
        return AprDisclosure(label = node.textOf("label"), locale = node.textOf("locale"))
    }

    private fun terminationRules(parent: Node): TerminationRules {
        val node = parent.obj("terminationRules")
        node.rejectUnknown(setOf("noticePeriodDays", "permittedGrounds", "defaultDpdThreshold"))
        node.require(setOf("noticePeriodDays", "permittedGrounds"))
        return TerminationRules(
            noticePeriodDays = node.int("noticePeriodDays"),
            permittedGrounds = node.strList("permittedGrounds").map { ground ->
                TerminationGround.entries.firstOrNull { it.name == ground }
                    ?: throw CompliancePackParseException("terminationRules.permittedGrounds: unknown ground '$ground'")
            }.toSet(),
            defaultDpdThreshold = node.optInt("defaultDpdThreshold") ?: 90,
        )
    }

    private fun disclosure(node: Node): PackDisclosure {
        node.rejectUnknown(setOf("id", "templateKey", "languages", "requiresAcknowledgement", "stage"))
        node.require(setOf("id", "templateKey", "languages", "stage"))
        return PackDisclosure(
            id = node.textOf("id"),
            templateKey = node.textOf("templateKey"),
            languages = node.strList("languages").toSet(),
            requiresAcknowledgement = node.bool("requiresAcknowledgement", defaultValue = false),
            stage = node.enumValue("stage", DisclosureStage.entries),
        )
    }

    private fun policyRule(node: Node): PolicyRule {
        node.rejectUnknown(setOf("id", "attribute", "operator", "threshold", "values", "band", "detail"))
        node.require(setOf("id", "attribute", "operator"))
        return PolicyRule(
            id = node.textOf("id"),
            attribute = node.enumValue("attribute", PolicyAttribute.entries),
            operator = node.enumValue("operator", PolicyOperator.entries),
            threshold = node.optDecimal("threshold"),
            values = node.strList("values").toSet(),
            band = node.optText("band"),
            detail = node.optText("detail") ?: "",
        )
    }
}

/** Typed accessor over one decoded JSON object; every misuse fails closed with [CompliancePackParseException]. */
private class Node(val raw: Map<String, Any?>, val where: String) {
    fun require(keys: Set<String>) {
        keys.forEach { key ->
            if (raw[key] == null) throw CompliancePackParseException("$where: missing mandatory key '$key'")
        }
    }

    fun rejectUnknown(known: Set<String>) {
        (raw.keys - known).forEach { key ->
            throw CompliancePackParseException("$where: unknown key '$key' (closed schema)")
        }
    }

    fun obj(key: String): Node =
        (raw[key] as? Map<*, *>)?.let { Node(it.entries.associate { e -> e.key.toString() to e.value }, key) }
            ?: throw CompliancePackParseException("$where: '$key' must be an object")

    @Suppress("UNCHECKED_CAST")
    fun objList(key: String): List<Map<String, Any?>> = (raw[key] as? List<Map<String, Any?>>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun strList(key: String): List<String> = (raw[key] as? List<String>) ?: emptyList()

    fun <T : Enum<T>> enumValue(key: String, entries: List<T>): T {
        val value = textOf(key)
        return entries.firstOrNull { it.name == value }
            ?: throw CompliancePackParseException(
                "$where: '$key': unknown value '$value' (allowed: ${entries.joinToString()})",
            )
    }
}

private fun Node.textOf(key: String): String =
    raw[key] as? String ?: throw CompliancePackParseException("$where: '$key' must be a string")

private fun Node.optText(key: String): String? = raw[key] as? String

private fun Node.int(key: String): Int =
    (raw[key] as? Number)?.toInt() ?: throw CompliancePackParseException("$where: '$key' must be a number")

private fun Node.optInt(key: String): Int? = (raw[key] as? Number)?.toInt()

private fun Node.bool(key: String, defaultValue: Boolean): Boolean = (raw[key] as? Boolean) ?: defaultValue

private fun Node.optDecimal(key: String): BigDecimal? = when (val v = raw[key]) {
    is Number -> BigDecimal(v.toString())
    is String -> v.toBigDecimalOrNull()
        ?: throw CompliancePackParseException("$where: '$key' must be a decimal number")
    else -> null
}

private fun Node.date(key: String): LocalDate = try {
    LocalDate.parse(textOf(key))
} catch (e: java.time.format.DateTimeParseException) {
    throw CompliancePackParseException("$where: '$key' must be an ISO date: ${e.message}")
}

private fun Node.optDate(key: String): LocalDate? = raw[key]?.let { date(key) }
