// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.productcatalog.domain.catalog.CatalogValue
import com.openbank.productcatalog.domain.catalog.EligibilityOperator
import com.openbank.productcatalog.domain.catalog.EligibilityRule
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.OfferingRelationship
import com.openbank.productcatalog.domain.catalog.PriceCadence
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.PriceKind
import com.openbank.productcatalog.domain.catalog.RelationshipKind
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.TaxTreatment
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.security.MessageDigest

@ApplicationScoped
class CatalogJson(private val mapper: ObjectMapper) {
    fun toValue(node: JsonNode): CatalogValue = when {
        node.isNull -> CatalogValue.NullValue
        node.isBoolean -> CatalogValue.BooleanValue(node.booleanValue())
        node.isTextual -> CatalogValue.TextValue(node.textValue())
        node.isNumber -> CatalogValue.DecimalValue(node.decimalValue())
        node.isArray -> CatalogValue.ArrayValue(node.map(::toValue))
        node.isObject -> CatalogValue.ObjectValue(node.fields().asSequence().associate { it.key to toValue(it.value) })
        else -> error("unsupported JSON node type ${node.nodeType}")
    }

    fun toObject(node: JsonNode): CatalogValue.ObjectValue = toValue(node) as? CatalogValue.ObjectValue
        ?: throw IllegalArgumentException("attributes must be a JSON object")

    fun toNode(value: CatalogValue): JsonNode = when (value) {
        CatalogValue.NullValue -> mapper.nodeFactory.nullNode()
        is CatalogValue.BooleanValue -> mapper.nodeFactory.booleanNode(value.value)
        is CatalogValue.TextValue -> mapper.nodeFactory.textNode(value.value)
        is CatalogValue.DecimalValue -> mapper.nodeFactory.numberNode(value.value)
        is CatalogValue.ArrayValue -> mapper.createArrayNode().also { array ->
            value.values.forEach { array.add(toNode(it)) }
        }
        is CatalogValue.ObjectValue -> mapper.createObjectNode().also { objectNode ->
            value.values.toSortedMap().forEach { (key, child) -> objectNode.set<JsonNode>(key, toNode(child)) }
        }
    }

    fun canonicalBytes(node: JsonNode): ByteArray = mapper.writeValueAsBytes(canonical(node))

    fun sha256(node: JsonNode): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalBytes(node))
        .joinToString("") { "%02x".format(it) }

    fun toContentNode(content: RevisionContent): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("name", mapper.valueToTree(content.name.values))
        content.description?.let { set<JsonNode>("description", mapper.valueToTree(it.values)) }
        set<JsonNode>("attributes", toNode(content.attributes))
        set<JsonNode>(
            "prices",
            mapper.valueToTree(
                content.prices.map { price ->
                    mapOf(
                        "code" to price.code,
                        "kind" to price.kind.name,
                        "value" to price.value.toPlainString(),
                        "currency" to price.currency,
                        "unit" to price.unit,
                        "cadence" to price.cadence.name,
                        "taxTreatment" to price.taxTreatment.name,
                        "effectiveFrom" to price.effectiveFrom,
                        "effectiveTo" to price.effectiveTo,
                    )
                },
            ),
        )
        set<JsonNode>("eligibility", mapper.valueToTree(content.eligibility.map(::eligibilityToMap)))
        set<JsonNode>("relationships", mapper.valueToTree(content.relationships))
        set<JsonNode>("documentCodes", mapper.valueToTree(content.documentCodes))
    }

    fun toContent(node: JsonNode): RevisionContent = RevisionContent(
        name = LocalizedText(mapper.convertValue(node.get("name"), MAP_TYPE)),
        description = node.get("description")?.let { LocalizedText(mapper.convertValue(it, MAP_TYPE)) },
        attributes = toObject(node.get("attributes")),
        prices = node.get("prices")?.map {
            PriceComponent(
                code = it.get("code").asText(),
                kind = PriceKind.valueOf(it.get("kind").asText()),
                value = it.get("value").asText().toBigDecimal(),
                currency = it.get("currency")?.takeUnless(JsonNode::isNull)?.asText(),
                unit = it.get("unit").asText(),
                cadence = PriceCadence.valueOf(it.get("cadence").asText()),
                taxTreatment = TaxTreatment.valueOf(it.get("taxTreatment").asText()),
                effectiveFrom = it.get("effectiveFrom")?.takeUnless(JsonNode::isNull)?.let { value ->
                    java.time.Instant.parse(value.asText())
                },
                effectiveTo = it.get("effectiveTo")?.takeUnless(JsonNode::isNull)?.let { value ->
                    java.time.Instant.parse(value.asText())
                },
            )
        }.orEmpty(),
        eligibility = node.get("eligibility")?.map {
            EligibilityRule(
                field = it.get("field").asText(),
                operator = EligibilityOperator.valueOf(it.get("operator").asText()),
                expected = toValue(it.get("expected")),
                explanation = LocalizedText(mapper.convertValue(it.get("explanation"), MAP_TYPE)),
            )
        }.orEmpty(),
        relationships = node.get("relationships")?.map {
            OfferingRelationship(
                kind = RelationshipKind.valueOf(it.get("kind").asText()),
                targetOfferingId = java.util.UUID.fromString(it.get("targetOfferingId").asText()),
            )
        }.orEmpty(),
        documentCodes = node.get("documentCodes")?.map(JsonNode::asText).orEmpty(),
    )

    private fun eligibilityToMap(rule: EligibilityRule): Map<String, Any> = mapOf(
        "field" to rule.field,
        "operator" to rule.operator.name,
        "expected" to toNode(rule.expected),
        "explanation" to rule.explanation.values,
    )

    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { result: ObjectNode ->
            node.fieldNames().asSequence().toList().sorted().forEach { key ->
                result.set<JsonNode>(key, canonical(node.get(key)))
            }
        }
        node.isArray -> mapper.createArrayNode().also { result: ArrayNode ->
            node.forEach { result.add(canonical(it)) }
        }
        node.isNumber -> mapper.nodeFactory.numberNode(BigDecimal(node.asText()))
        else -> node.deepCopy()
    }

    private companion object {
        val MAP_TYPE = mapperType()

        fun mapperType(): com.fasterxml.jackson.databind.JavaType =
            ObjectMapper().typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)
    }
}
