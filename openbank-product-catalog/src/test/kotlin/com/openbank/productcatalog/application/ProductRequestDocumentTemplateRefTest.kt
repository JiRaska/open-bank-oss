// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.productcatalog.domain.TermsAndConditions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-0162 D1: a product's [TermsAndConditions] references an `openbank-document-service` template
 * by `code` only (not a pinned version) — the reference must survive [ProductRequest.toDomain] and
 * [ProductRequest.applyTo] unchanged, since it's the whole point of the "code, not (code, version)"
 * choice: a document-service republish must never require touching the product.
 */
class ProductRequestDocumentTemplateRefTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `toDomain carries documentTemplateCode through unchanged`() {
        val tac = termsAndConditions(documentTemplateCode = "VOP_CS")
        val request = baseRequest(termsAndConditions = listOf(tac))

        val product = request.toDomain(clock)

        assertThat(product.termsAndConditions).hasSize(1)
        assertThat(product.termsAndConditions.single().documentTemplateCode).isEqualTo("VOP_CS")
    }

    @Test
    fun `documentTemplateCode is nullable and defaults to null when not supplied`() {
        val tac = termsAndConditions(documentTemplateCode = null)
        val request = baseRequest(termsAndConditions = listOf(tac))

        val product = request.toDomain(clock)

        assertThat(product.termsAndConditions.single().documentTemplateCode).isNull()
    }

    @Test
    fun `applyTo replaces the whole termsAndConditions list, carrying the ref along`() {
        val existing = baseRequest(
            termsAndConditions = listOf(termsAndConditions(documentTemplateCode = "VOP_CS")),
        ).toDomain(clock)
        val update = baseRequest(
            termsAndConditions = listOf(termsAndConditions(documentTemplateCode = "RAMCOVA_SMLOUVA_CS")),
        )

        val updated = update.applyTo(existing, clock)

        assertThat(updated.termsAndConditions.single().documentTemplateCode).isEqualTo("RAMCOVA_SMLOUVA_CS")
    }

    @Test
    fun `applyTo keeps the existing termsAndConditions (and its ref) when the request omits them`() {
        val existing = baseRequest(
            termsAndConditions = listOf(termsAndConditions(documentTemplateCode = "VOP_CS")),
        ).toDomain(clock)
        val update = baseRequest(termsAndConditions = null)

        val updated = update.applyTo(existing, clock)

        assertThat(updated.termsAndConditions.single().documentTemplateCode).isEqualTo("VOP_CS")
    }

    private fun termsAndConditions(documentTemplateCode: String?) = TermsAndConditions(
        version = "1.1.0",
        url = "https://openbank.example/tac/savings/v1.1",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        language = "cs",
        documentTemplateCode = documentTemplateCode,
    )

    private fun baseRequest(termsAndConditions: List<TermsAndConditions>?) = ProductRequest(
        code = "SAVINGS_STD",
        name = "Standard savings",
        type = "SAVINGS",
        currency = "CZK",
        termsAndConditions = termsAndConditions,
    )
}
