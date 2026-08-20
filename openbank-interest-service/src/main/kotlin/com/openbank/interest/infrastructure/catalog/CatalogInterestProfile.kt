// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.catalog

import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.client.CatalogOfferingClientResponse
import com.openbank.interest.infrastructure.client.CatalogRevisionClientResponse
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal data class CatalogInterestProfile(
    val revisionId: UUID,
    val offeringId: UUID,
    val specificationId: UUID,
    val schemaId: String,
    val schemaVersion: Int,
    val contentHash: String,
    val currency: String,
    val annualRate: BigDecimal,
    val dayCount: DayCount,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
)

/** A rejected catalog profile is an auditable outcome, never a best-effort rate conversion. */
internal class CatalogInterestProfileRejected(message: String) : IllegalArgumentException(message)

/**
 * Maps only the fixed-rate subset the daily accrual engine can prove today.
 *
 * Tiered profiles are deliberately rejected: their catalog schema does not state whether tiers are
 * marginal or whole-balance, so treating a tier as one fixed rate would produce incorrect money.
 */
internal object CatalogInterestProfileParser {
    private const val DEPOSIT_SCHEMA = "org.openbank.banking.deposit"
    private const val FIXED = "FIXED"

    fun parse(
        revision: CatalogRevisionClientResponse,
        offering: CatalogOfferingClientResponse,
    ): CatalogInterestProfile {
        require(revision.state == "PUBLISHED") { "revision is not published" }
        require(revision.offeringId == offering.id) { "revision/offering identity mismatch" }
        require(revision.schemaRef.id == DEPOSIT_SCHEMA) { "unsupported catalog schema ${revision.schemaRef.id}" }
        val hash = requireNotNull(revision.contentHash) { "published revision has no content hash" }
        val attributes = revision.content.attributes
        val currency = attributes.requiredText("currency", "currency").also {
            require(it.matches(Regex("[A-Z]{3}"))) { "currency must be ISO-4217" }
        }
        val interest = attributes.requiredObject("interest")
        require(interest.requiredText("rateType", "interest.rateType") == FIXED) {
            "unsupported interest.rateType; only FIXED is executable"
        }
        val annualRate = interest.requiredDecimal("annualRate", "interest.annualRate")
        require(annualRate >= BigDecimal.ZERO) { "interest.annualRate must be non-negative" }
        val dayCount = try {
            DayCount.valueOf(interest.requiredText("dayCount", "interest.dayCount"))
        } catch (_: IllegalArgumentException) {
            throw CatalogInterestProfileRejected("unsupported interest.dayCount")
        }
        val from = requireMidnight(revision.effectiveFrom, "effectiveFrom")
        val to = revision.effectiveTo?.let { requireMidnight(it, "effectiveTo").minusDays(1) }
        require(to == null || to >= from) { "effective interval contains no accrual date" }
        return CatalogInterestProfile(
            revisionId = revision.id,
            offeringId = offering.id,
            specificationId = offering.specificationId,
            schemaId = revision.schemaRef.id,
            schemaVersion = revision.schemaRef.version,
            contentHash = hash,
            currency = currency,
            annualRate = annualRate,
            dayCount = dayCount,
            effectiveFrom = from,
            effectiveTo = to,
        )
    }

    private fun requireMidnight(value: OffsetDateTime?, name: String): LocalDate {
        requireNotNull(value) { "$name is required for a daily interest profile" }
        val utc = value.withOffsetSameInstant(ZoneOffset.UTC)
        require(utc.toLocalTime().toSecondOfDay() == 0 && utc.nano == 0) {
            "$name must be aligned to midnight UTC for daily accrual"
        }
        return utc.toLocalDate()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.requiredObject(name: String) = get(name).also {
        require(it != null && it.isObject) { "$name must be an object" }
    }!!

    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String, path: String): String =
        get(name)?.asText()
            ?.takeIf { it.isNotBlank() }
            ?: throw CatalogInterestProfileRejected("$path is required")

    private fun com.fasterxml.jackson.databind.JsonNode.requiredDecimal(name: String, path: String): BigDecimal {
        val text = requiredText(name, path)
        require(text.matches(Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?"))) { "$path must be a canonical decimal" }
        return text.toBigDecimal()
    }
}
