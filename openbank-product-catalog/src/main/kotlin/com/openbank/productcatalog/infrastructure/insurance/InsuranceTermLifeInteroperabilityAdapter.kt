// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.insurance

import com.openbank.productcatalog.domain.catalog.CatalogValue

/**
 * Bounded, lossless projections of the trusted term-life pack into interoperability-shaped data.
 *
 * This is intentionally an adapter, not a kernel type and not a full implementation of ACORD or
 * TMF620. It exists so an insurer can prove that the reference-pack terms survive the two common
 * exchange shapes without adding insurance fields or a dependency on either standard to Catalog.
 */
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
        "productSpecCharacteristic" to listOf(
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

    private fun characteristic(name: String, value: Any) = mapOf("name" to name, "value" to value)

    private fun objectValue(root: CatalogValue.ObjectValue, name: String): Map<String, Any> =
        (root.values[name] as? CatalogValue.ObjectValue)?.toWire()
            ?: throw IllegalArgumentException("term-life attributes require object '$name'")

    private fun optionalObject(root: CatalogValue.ObjectValue, name: String): Map<String, Any>? =
        (root.values[name] as? CatalogValue.ObjectValue)?.toWire()

    private fun arrayObjects(root: CatalogValue.ObjectValue, name: String): List<Map<String, Any>> =
        ((root.values[name] as? CatalogValue.ArrayValue)?.values ?: throw IllegalArgumentException(
            "term-life attributes require array '$name'",
        )).mapIndexed { index, value ->
            (value as? CatalogValue.ObjectValue)?.toWire()
                ?: throw IllegalArgumentException("term-life '$name' element $index must be an object")
        }

    private fun text(root: CatalogValue.ObjectValue, name: String): String =
        (root.values[name] as? CatalogValue.TextValue)?.value
            ?: throw IllegalArgumentException("term-life attributes require text '$name'")

    private fun textOrDecimal(root: CatalogValue.ObjectValue, name: String): Any = when (val value = root.values[name]) {
        is CatalogValue.TextValue -> value.value
        is CatalogValue.DecimalValue -> value.value.toPlainString()
        else -> throw IllegalArgumentException("term-life attributes require text or decimal '$name'")
    }

    private fun CatalogValue.ObjectValue.toWire(): Map<String, Any> = values.mapValues { (_, value) -> value.toWireValue() }

    private fun CatalogValue.toWireValue(): Any = when (this) {
        CatalogValue.NullValue -> ""
        is CatalogValue.BooleanValue -> value
        is CatalogValue.TextValue -> value
        is CatalogValue.DecimalValue -> value.toPlainString()
        is CatalogValue.ArrayValue -> values.map { it.toWireValue() }
        is CatalogValue.ObjectValue -> toWire()
    }
}
