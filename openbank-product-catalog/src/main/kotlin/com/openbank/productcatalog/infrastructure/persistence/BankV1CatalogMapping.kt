// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.domain.Fee
import com.openbank.productcatalog.domain.Product
import com.openbank.productcatalog.domain.catalog.CatalogValue
import com.openbank.productcatalog.domain.catalog.LocalizedText
import com.openbank.productcatalog.domain.catalog.PriceCadence
import com.openbank.productcatalog.domain.catalog.PriceComponent
import com.openbank.productcatalog.domain.catalog.PriceKind
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.TaxTreatment
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

/** The only place where the legacy banking document is translated to the generic catalog model. */
@ApplicationScoped
class BankV1CatalogMapping(private val mapper: ObjectMapper, private val catalogJson: CatalogJson) {
    fun contentOf(product: Product): RevisionContent = RevisionContent(
        name = LocalizedText(mapOf("en" to product.name)),
        description = product.description?.takeIf(String::isNotBlank)?.let { LocalizedText(mapOf("en" to it)) },
        attributes = CatalogValue.ObjectValue(
            mapOf(
                "productType" to CatalogValue.TextValue(product.type),
                "currency" to CatalogValue.TextValue(product.currency),
                LEGACY_DOCUMENT to CatalogValue.TextValue(mapper.writeValueAsString(product)),
            ),
        ),
        prices = buildList {
            if (product.baseRate >= 0.0) add(product.baseRate.baseRatePrice())
            product.fees.forEach { add(it.toPrice()) }
        },
        documentCodes = product.termsAndConditions.mapNotNull { it.documentTemplateCode }.distinct(),
    )

    fun legacyProduct(revision: CatalogRevisionEntity): Product {
        val content = catalogJson.toContent(mapper.readTree(revision.content.encode()))
        val legacyJson = (content.attributes.values[LEGACY_DOCUMENT] as? CatalogValue.TextValue)?.value
            ?: error("mapped banking revision has no lossless legacyDocument")
        return mapper.readValue(legacyJson, Product::class.java)
    }

    fun toEntity(revision: ProductRevision) = CatalogRevisionEntity().also {
        it.id = revision.id
        it.offeringId = revision.offeringId
        it.number = revision.number
        it.schemaId = revision.schemaRef.id
        it.schemaVersion = revision.schemaRef.version
        it.state = revision.state.name
        it.content = JsonObject(catalogJson.toContentNode(revision.content).toString())
        it.effectiveFrom = revision.effectiveFrom
        it.effectiveTo = revision.effectiveTo
        it.makerId = revision.makerId
        it.checkerId = revision.checkerId
        it.reason = revision.reason
        it.contentHash = revision.contentHash
        it.createdAt = revision.createdAt
        it.updatedAt = revision.updatedAt
        it.revision = revision.revision
    }

    fun priceEntities(revision: ProductRevision): List<CatalogPriceEntity> = revision.content.prices.map { price ->
        CatalogPriceEntity().apply {
            id = UUID.randomUUID()
            revisionId = revision.id
            code = price.code
            kind = price.kind.name
            value = price.value
            currency = price.currency
            unit = price.unit
            cadence = price.cadence.name
            taxTreatment = price.taxTreatment.name
            effectiveFrom = price.effectiveFrom
            effectiveTo = price.effectiveTo
        }
    }

    fun applyProjection(entity: ProductEntity, product: Product) {
        entity.code = product.code
        entity.type = product.type
        entity.status = product.status.name
        entity.currency = product.currency
        entity.doc = mapper.writeValueAsString(product)
    }

    private fun Double.baseRatePrice() = PriceComponent(
        code = "BASE_RATE",
        kind = PriceKind.RATE,
        value = BigDecimal.valueOf(this),
        unit = "annual-rate",
        cadence = PriceCadence.ANNUALLY,
        taxTreatment = TaxTreatment.UNSPECIFIED,
    )

    private fun Fee.toPrice() = PriceComponent(
        code = "FEE_${id.uppercase().replace(NON_CODE_CHARACTER, "_").take(MAX_FEE_CODE_SUFFIX_LENGTH)}",
        kind = PriceKind.AMOUNT,
        value = BigDecimal.valueOf(amount),
        currency = currency,
        unit = frequency.lowercase(),
        cadence = when (frequency.uppercase()) {
            "DAILY" -> PriceCadence.DAILY
            "MONTHLY" -> PriceCadence.MONTHLY
            "QUARTERLY" -> PriceCadence.QUARTERLY
            "ANNUAL", "ANNUALLY", "YEARLY" -> PriceCadence.ANNUALLY
            "ONE_TIME", "ONCE" -> PriceCadence.ONCE
            else -> PriceCadence.USAGE
        },
        taxTreatment = TaxTreatment.UNSPECIFIED,
    )

    companion object {
        const val LEGACY_DOCUMENT = "legacyDocument"
        private const val MAX_FEE_CODE_SUFFIX_LENGTH = 59
        private val NON_CODE_CHARACTER = Regex("[^A-Z0-9]+")
    }
}
