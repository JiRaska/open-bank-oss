// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.insurance

import com.openbank.productcatalog.domain.catalog.CatalogValue
import java.math.BigDecimal

/**
 * Bounded, lossless projections of the trusted term-life pack into interoperability-shaped data.
 *
 * This is intentionally an adapter, not a kernel type and not a full implementation of ACORD or
 * TMF620. It exists so an insurer can prove that the reference-pack terms survive the two common
 * exchange shapes without adding insurance fields or a dependency on either standard to Catalog.
 */
@Suppress("TooManyFunctions") // The closed import profile keeps each field's validation explicit and reviewable.
class InsuranceTermLifeInteroperabilityAdapter {

    fun toAcordProfile(attributes: CatalogValue.ObjectValue): Map<String, Any> = buildMap {
        put("productType", "TERM_LIFE")
        put("coverage", objectValue(attributes, "coverage"))
        put("termYears", textOrDecimal(attributes, "termYears"))
        put("premiumModel", text(attributes, "premiumModel"))
        optionalObject(attributes, "premium")?.let { put("premium", it) }
        put("insuredEvents", arrayObjects(attributes, "perils"))
        put("exclusions", arrayObjects(attributes, "exclusions"))
        put("limits", arrayObjects(attributes, "limits"))
        put("deductibles", arrayObjects(attributes, "deductibles"))
        put("underwritingQuestions", arrayObjects(attributes, "underwritingQuestions"))
    }

    fun toTmf620Profile(attributes: CatalogValue.ObjectValue): Map<String, Any> = mapOf(
        "@type" to "ProductSpecification",
        "name" to "Term life insurance",
        "productSpecCharacteristic" to buildList {
            addAll(
                listOf(
                    characteristic("coverage", objectValue(attributes, "coverage")),
                    characteristic("termYears", textOrDecimal(attributes, "termYears")),
                    characteristic("premiumModel", text(attributes, "premiumModel")),
                    characteristic("perils", arrayObjects(attributes, "perils")),
                    characteristic("exclusions", arrayObjects(attributes, "exclusions")),
                    characteristic("limits", arrayObjects(attributes, "limits")),
                    characteristic("deductibles", arrayObjects(attributes, "deductibles")),
                    characteristic("underwritingQuestions", arrayObjects(attributes, "underwritingQuestions")),
                ),
            )
            optionalObject(attributes, "premium")?.let { add(characteristic("premium", it)) }
        },
    )

    /**
     * Imports precisely the bounded ACORD profile emitted by [toAcordProfile]. It is deliberately
     * closed: an integration field without a catalog mapping fails instead of disappearing.
     */
    fun fromAcordProfile(profile: Map<String, Any?>): CatalogValue.ObjectValue {
        val values = requireExactKeys(
            profile,
            required = ACORD_REQUIRED_FIELDS,
            optional = setOf("premium"),
            profileName = "ACORD term-life profile",
        )
        require(values.getValue("productType") == "TERM_LIFE") { "ACORD profile must be TERM_LIFE" }
        return attributesFromFields(
            ImportedTermLifeFields(
                pricing = ImportedTermLifePricing(
                    coverage = requiredObject(values, "coverage"),
                    termYears = decimalTermYears(values.getValue("termYears")),
                    premiumModel = requiredText(values, "premiumModel"),
                    premium = values["premium"]?.let { objectValue(it, "premium") },
                ),
                conditions = ImportedTermLifeConditions(
                    perils = requiredObjects(values, "insuredEvents"),
                    exclusions = requiredObjects(values, "exclusions"),
                    limits = requiredObjects(values, "limits"),
                    deductibles = requiredObjects(values, "deductibles"),
                    underwritingQuestions = requiredObjects(values, "underwritingQuestions"),
                ),
            ),
        )
    }

    /**
     * Imports the closed TMF620 ProductSpecification profile emitted by [toTmf620Profile]. This
     * is a version-pinned exchange profile, not a permissive TMF620 parser.
     */
    fun fromTmf620Profile(profile: Map<String, Any?>): CatalogValue.ObjectValue {
        val values = requireExactKeys(
            profile,
            required = TMF_REQUIRED_FIELDS,
            optional = emptySet(),
            profileName = "TMF620 term-life profile",
        )
        require(values.getValue("@type") == "ProductSpecification") {
            "TMF620 profile must be a ProductSpecification"
        }
        require(values.getValue("name") == "Term life insurance") { "TMF620 profile has an unsupported name" }
        val characteristics = values.getValue("productSpecCharacteristic") as? List<*>
            ?: throw IllegalArgumentException("TMF620 profile requires productSpecCharacteristic array")
        val fields = characteristics.associateByStrict("TMF620 characteristic") { raw ->
            val characteristic = objectValue(raw, "TMF620 characteristic")
            requireExactKeys(
                characteristic,
                required = setOf("name", "value"),
                optional = emptySet(),
                profileName = "TMF620 characteristic",
            )
            requiredText(characteristic, "name") to characteristic.getValue("value")
        }
        val permitted = TMF_CHARACTERISTICS + "premium"
        require(fields.keys.all(permitted::contains)) {
            "TMF620 profile contains unsupported characteristic(s): ${fields.keys - permitted}"
        }
        require(TMF_CHARACTERISTICS.all(fields::containsKey)) {
            "TMF620 profile is missing required characteristic(s): ${TMF_CHARACTERISTICS - fields.keys}"
        }
        return attributesFromFields(
            ImportedTermLifeFields(
                pricing = ImportedTermLifePricing(
                    coverage = objectValue(fields.getValue("coverage"), "coverage"),
                    termYears = decimalTermYears(fields.getValue("termYears")),
                    premiumModel = textValue(fields.getValue("premiumModel"), "premiumModel"),
                    premium = fields["premium"]?.let { objectValue(it, "premium") },
                ),
                conditions = ImportedTermLifeConditions(
                    perils = objectArray(fields.getValue("perils"), "perils"),
                    exclusions = objectArray(fields.getValue("exclusions"), "exclusions"),
                    limits = objectArray(fields.getValue("limits"), "limits"),
                    deductibles = objectArray(fields.getValue("deductibles"), "deductibles"),
                    underwritingQuestions = objectArray(
                        fields.getValue("underwritingQuestions"),
                        "underwritingQuestions",
                    ),
                ),
            ),
        )
    }

    private fun characteristic(name: String, value: Any): Map<String, Any> = mapOf("name" to name, "value" to value)

    private fun objectValue(root: CatalogValue.ObjectValue, name: String): Map<String, Any> =
        (root.values[name] as? CatalogValue.ObjectValue)?.toWire()
            ?: throw IllegalArgumentException("term-life attributes require object '$name'")

    private fun optionalObject(root: CatalogValue.ObjectValue, name: String): Map<String, Any>? =
        (root.values[name] as? CatalogValue.ObjectValue)?.toWire()

    private fun arrayObjects(root: CatalogValue.ObjectValue, name: String): List<Map<String, Any>> {
        val values = (root.values[name] as? CatalogValue.ArrayValue)?.values
            ?: throw IllegalArgumentException("term-life attributes require array '$name'")
        return values.mapIndexed { index, value ->
            (value as? CatalogValue.ObjectValue)?.toWire()
                ?: throw IllegalArgumentException("term-life '$name' element $index must be an object")
        }
    }

    private fun text(root: CatalogValue.ObjectValue, name: String): String =
        (root.values[name] as? CatalogValue.TextValue)?.value
            ?: throw IllegalArgumentException("term-life attributes require text '$name'")

    private fun textOrDecimal(root: CatalogValue.ObjectValue, name: String): Any =
        when (val value = root.values[name]) {
            is CatalogValue.TextValue -> value.value
            is CatalogValue.DecimalValue -> value.value.toPlainString()
            else -> throw IllegalArgumentException("term-life attributes require text or decimal '$name'")
        }

    private fun attributesFromFields(fields: ImportedTermLifeFields): CatalogValue.ObjectValue {
        requireExactKeys(fields.pricing.coverage, setOf("amount", "currency"), emptySet(), "term-life coverage")
        fields.pricing.premium?.let {
            requireExactKeys(it, setOf("amount", "currency", "cadence"), emptySet(), "term-life premium")
        }
        fields.conditions.perils.forEach {
            requireExactKeys(it, setOf("code", "description"), emptySet(), "term-life peril")
        }
        fields.conditions.exclusions.forEach {
            requireExactKeys(it, setOf("code", "description"), emptySet(), "term-life exclusion")
        }
        fields.conditions.limits.forEach {
            requireExactKeys(it, setOf("kind", "amount", "currency"), emptySet(), "term-life limit")
        }
        fields.conditions.deductibles.forEach {
            requireExactKeys(it, setOf("kind", "amount", "currency"), emptySet(), "term-life deductible")
        }
        fields.conditions.underwritingQuestions.forEach {
            requireExactKeys(
                it,
                setOf("id", "question", "answerType", "required"),
                emptySet(),
                "term-life underwriting question",
            )
        }
        return CatalogValue.ObjectValue(
            buildMap {
                put("coverage", fields.pricing.coverage.toCatalogObject())
                put("termYears", fields.pricing.termYears)
                put("premiumModel", CatalogValue.TextValue(fields.pricing.premiumModel))
                fields.pricing.premium?.let { put("premium", it.toCatalogObject()) }
                put("perils", fields.conditions.perils.toCatalogArray())
                put("exclusions", fields.conditions.exclusions.toCatalogArray())
                put("limits", fields.conditions.limits.toCatalogArray())
                put("deductibles", fields.conditions.deductibles.toCatalogArray())
                put("underwritingQuestions", fields.conditions.underwritingQuestions.toCatalogArray())
            },
        )
    }

    private fun requireExactKeys(
        raw: Map<String, Any?>,
        required: Set<String>,
        optional: Set<String>,
        profileName: String,
    ): Map<String, Any?> {
        val permitted = required + optional
        require(raw.keys.all(permitted::contains)) {
            "$profileName contains unsupported field(s): ${raw.keys - permitted}"
        }
        require(required.all(raw::containsKey)) {
            "$profileName is missing required field(s): ${required - raw.keys}"
        }
        return raw
    }

    private fun List<*>.associateByStrict(name: String, entry: (Any?) -> Pair<String, Any?>): Map<String, Any?> =
        buildMap {
            this@associateByStrict.forEach { raw ->
                val (key, value) = entry(raw)
                require(put(key, value) == null) { "$name '$key' is duplicated" }
            }
        }

    private fun requiredObject(root: Map<String, Any?>, name: String): Map<String, Any?> =
        objectValue(root.getValue(name), name)

    private fun requiredObjects(root: Map<String, Any?>, name: String): List<Map<String, Any?>> =
        objectArray(root.getValue(name), name)

    private fun requiredText(root: Map<String, Any?>, name: String): String = textValue(root.getValue(name), name)

    private fun textValue(value: Any?, name: String): String =
        value as? String ?: throw IllegalArgumentException("term-life '$name' must be text")

    private fun decimalTermYears(value: Any?): CatalogValue.DecimalValue = when (value) {
        is BigDecimal -> CatalogValue.DecimalValue(value)
        is Number -> CatalogValue.DecimalValue(value.toString().toBigDecimal())
        is String -> CatalogValue.DecimalValue(value.toBigDecimal())
        else -> throw IllegalArgumentException("term-life 'termYears' must be a decimal or decimal string")
    }

    private fun objectValue(value: Any?, name: String): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (key, child) ->
            (key as? String ?: throw IllegalArgumentException("term-life '$name' has a non-text key")) to child
        } ?: throw IllegalArgumentException("term-life '$name' must be an object")

    private fun objectArray(value: Any?, name: String): List<Map<String, Any?>> =
        (value as? List<*>)?.mapIndexed { index, child -> objectValue(child, "$name element $index") }
            ?: throw IllegalArgumentException("term-life '$name' must be an array")

    private fun Map<String, Any?>.toCatalogObject(): CatalogValue.ObjectValue =
        CatalogValue.ObjectValue(mapValues { (_, value) -> value.toCatalogValue() })

    private fun List<Map<String, Any?>>.toCatalogArray(): CatalogValue.ArrayValue =
        CatalogValue.ArrayValue(map { it.toCatalogObject() })

    private fun Any?.toCatalogValue(): CatalogValue = when (this) {
        null -> CatalogValue.NullValue
        is Boolean -> CatalogValue.BooleanValue(this)
        is String -> CatalogValue.TextValue(this)
        is BigDecimal -> CatalogValue.DecimalValue(this)
        is Number -> CatalogValue.DecimalValue(toString().toBigDecimal())
        is Map<*, *> -> objectValue(this, "nested object").toCatalogObject()
        is List<*> -> CatalogValue.ArrayValue(map { it.toCatalogValue() })
        else -> throw IllegalArgumentException(
            "term-life profile contains unsupported value type ${this::class.simpleName}",
        )
    }

    private fun CatalogValue.ObjectValue.toWire(): Map<String, Any> = values.mapValues { (_, value) ->
        value.toWireValue()
    }

    private fun CatalogValue.toWireValue(): Any = when (this) {
        CatalogValue.NullValue -> ""
        is CatalogValue.BooleanValue -> value
        is CatalogValue.TextValue -> value
        is CatalogValue.DecimalValue -> value.toPlainString()
        is CatalogValue.ArrayValue -> values.map { it.toWireValue() }
        is CatalogValue.ObjectValue -> toWire()
    }

    private companion object {
        data class ImportedTermLifeFields(
            val pricing: ImportedTermLifePricing,
            val conditions: ImportedTermLifeConditions,
        )

        data class ImportedTermLifePricing(
            val coverage: Map<String, Any?>,
            val termYears: CatalogValue.DecimalValue,
            val premiumModel: String,
            val premium: Map<String, Any?>?,
        )

        data class ImportedTermLifeConditions(
            val perils: List<Map<String, Any?>>,
            val exclusions: List<Map<String, Any?>>,
            val limits: List<Map<String, Any?>>,
            val deductibles: List<Map<String, Any?>>,
            val underwritingQuestions: List<Map<String, Any?>>,
        )

        val ACORD_REQUIRED_FIELDS = setOf(
            "productType",
            "coverage",
            "termYears",
            "premiumModel",
            "insuredEvents",
            "exclusions",
            "limits",
            "deductibles",
            "underwritingQuestions",
        )
        val TMF_REQUIRED_FIELDS = setOf("@type", "name", "productSpecCharacteristic")
        val TMF_CHARACTERISTICS = setOf(
            "coverage",
            "termYears",
            "premiumModel",
            "perils",
            "exclusions",
            "limits",
            "deductibles",
            "underwritingQuestions",
        )
    }
}
