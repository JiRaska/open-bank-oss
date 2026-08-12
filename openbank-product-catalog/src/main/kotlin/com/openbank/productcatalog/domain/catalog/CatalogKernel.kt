// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain.catalog

import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Exact immutable reference to an industry-pack schema. */
data class SchemaRef(val id: String, val version: Int) {
    init {
        require(ID_PATTERN.matches(id)) { "schema id must be a reverse-DNS name" }
        require(version > 0) { "schema version must be positive" }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){2,}$")
    }
}

/** Framework-free JSON value algebra; decimal values never pass through binary floating point. */
sealed interface CatalogValue {
    data object NullValue : CatalogValue
    data class BooleanValue(val value: Boolean) : CatalogValue
    data class TextValue(val value: String) : CatalogValue
    data class DecimalValue(val value: BigDecimal) : CatalogValue
    data class ArrayValue(val values: List<CatalogValue>) : CatalogValue
    data class ObjectValue(val values: Map<String, CatalogValue>) : CatalogValue
}

data class LocalizedText(val values: Map<String, String>) {
    init {
        require(values.isNotEmpty()) { "localized text must contain at least one locale" }
        values.forEach { (locale, text) ->
            require(locale.isValidLanguageTag()) { "locale '$locale' must be a valid BCP 47 language tag" }
            require(text.isNotBlank()) { "localized text for '$locale' must not be blank" }
        }
    }
}

enum class RevisionState { DRAFT, PUBLISHED, SUPERSEDED }
enum class PriceKind { AMOUNT, RATE }
enum class PriceCadence { ONCE, DAILY, MONTHLY, QUARTERLY, ANNUALLY, USAGE }
enum class TaxTreatment { INCLUSIVE, EXCLUSIVE, EXEMPT, UNSPECIFIED }
enum class EligibilityOperator { EQUALS, NOT_EQUALS, IN, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL }
enum class RelationshipKind { BUNDLE, ADD_ON, REPLACEMENT, DEPENDENCY, COMPATIBLE_WITH }

data class ProductSpecification(
    val id: UUID = UUID.randomUUID(),
    val code: String,
    val schemaRef: SchemaRef,
    val createdAt: Instant,
    val revision: Long = 0,
) {
    init {
        require(CODE_PATTERN.matches(code)) { "catalog item code must match ${CODE_PATTERN.pattern}" }
        require(revision >= 0) { "revision must not be negative" }
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    }
}

data class MarketContext(
    val brands: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val channels: Set<String> = emptySet(),
    val segments: Set<String> = emptySet(),
    val locales: Set<String> = emptySet(),
) {
    init {
        require(countries.all { it.matches(Regex("^[A-Z]{2}$")) }) { "countries must be ISO alpha-2 codes" }
        require(locales.all(String::isValidLanguageTag)) { "market locales must be valid BCP 47 tags" }
    }
}

data class PriceComponent(
    val code: String,
    val kind: PriceKind,
    val value: BigDecimal,
    val currency: String? = null,
    val unit: String,
    val cadence: PriceCadence,
    val taxTreatment: TaxTreatment = TaxTreatment.UNSPECIFIED,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
) {
    init {
        require(code.isNotBlank()) { "price code must not be blank" }
        require(value >= BigDecimal.ZERO) { "price value must not be negative" }
        require(kind != PriceKind.AMOUNT || currency?.matches(Regex("^[A-Z]{3}$")) == true) {
            "amount price requires an uppercase ISO currency code"
        }
        require(unit.isNotBlank()) { "price unit must not be blank" }
        require(effectiveFrom == null || effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
            "price effectiveTo must be after effectiveFrom"
        }
    }
}

data class EligibilityRule(
    val field: String,
    val operator: EligibilityOperator,
    val expected: CatalogValue,
    val explanation: LocalizedText,
) {
    init {
        require(field.isNotBlank()) { "eligibility field must not be blank" }
    }
}

data class OfferingRelationship(val kind: RelationshipKind, val targetOfferingId: UUID)

data class RevisionContent(
    val name: LocalizedText,
    val description: LocalizedText? = null,
    val attributes: CatalogValue.ObjectValue,
    val prices: List<PriceComponent> = emptyList(),
    val eligibility: List<EligibilityRule> = emptyList(),
    val relationships: List<OfferingRelationship> = emptyList(),
    val documentCodes: List<String> = emptyList(),
) {
    init {
        require(prices.map(PriceComponent::code).distinct().size == prices.size) { "price codes must be unique" }
        require(documentCodes.none(String::isBlank)) { "document codes must not contain blanks" }
        require(documentCodes.distinct().size == documentCodes.size) { "document codes must be unique" }
    }
}

data class ProductOffering(
    val id: UUID = UUID.randomUUID(),
    val specificationId: UUID,
    val code: String,
    val market: MarketContext = MarketContext(),
    val revision: Long = 0,
) {
    init {
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{1,63}$"))) { "offering code is invalid" }
        require(revision >= 0) { "revision must not be negative" }
    }
}

data class ProductRevision(
    val id: UUID = UUID.randomUUID(),
    val offeringId: UUID,
    val number: Long,
    val schemaRef: SchemaRef,
    val state: RevisionState = RevisionState.DRAFT,
    val content: RevisionContent,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
    val makerId: String,
    val checkerId: String? = null,
    val reason: String? = null,
    val contentHash: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long = 0,
) {
    init {
        require(number > 0) { "revision number must be positive" }
        require(makerId.isNotBlank()) { "makerId must not be blank" }
        require(effectiveFrom == null || effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
            "effectiveTo must be after effectiveFrom"
        }
        require(state != RevisionState.PUBLISHED || checkerId?.isNotBlank() == true) {
            "published revision requires checkerId"
        }
        require(state != RevisionState.PUBLISHED || makerId != checkerId) {
            "checker must differ from maker"
        }
        require(revision >= 0) { "revision must not be negative" }
    }
}

/** Stable integration envelope persisted atomically with every accepted catalog change. */
data class CatalogChangeEvent(
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val actorId: String,
) {
    init {
        require(aggregateType.isNotBlank()) { "aggregateType must not be blank" }
        require(eventType.matches(Regex("^com\\.openbank\\.catalog\\.[a-z0-9_]+$"))) {
            "eventType must use the catalog event namespace"
        }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(actorId.isNotBlank()) { "actorId must not be blank" }
    }
}

private fun String.isValidLanguageTag(): Boolean = isNotBlank() && Locale.forLanguageTag(this).toLanguageTag() != "und"
