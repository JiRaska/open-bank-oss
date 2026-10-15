// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.document

import com.openbank.pension.application.onboarding.KidRequest
import com.openbank.pension.application.port.out.AnnualStatementContent
import com.openbank.pension.domain.incentive.TaxYearSummary
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.KeyInformationDocumentType
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The cs/en template sources (`document-templates/`) against what the adapters SEND: every
 * template code a render can ask for exists in both languages, carries the legal-review marker,
 * and references only variables its data map supplies — a variable the map lacks renders as an
 * empty cell in a legal document, silently.
 */
class PensionDocumentTemplatesTest {
    private fun source(code: String): String? =
        javaClass.classLoader.getResource("document-templates/$code.hbs")?.readText()

    private val variable = Regex("""\{\{\s*(?:#if\s+)?([A-Za-z][A-Za-z0-9]*)\s*}}""")

    private val zero = BigDecimal("0.00")
    private val summary = TaxYearSummary(
        UUID.randomUUID(), 2025, "CZK", zero, zero, zero, zero, zero, null, emptyMap(), emptyList(),
    )
    private val kidData = PensionDocumentData.kid(
        KidRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            KeyInformationDocumentType.PRIIPS_KID,
            "x",
            ProductLine.DIP,
            "BALANCED",
            "cs",
        ),
    )

    private val cases: Map<String, Map<String, Any?>> =
        OnboardingRulesLoader.loadAll().associate { it.keyInformationDocument.templateCode to kidData } +
            mapOf(
                PensionDocumentTemplates.TAX_CERTIFICATE to PensionDocumentData.taxCertificate(summary, "123"),
                PensionDocumentTemplates.ANNUAL_STATEMENT to PensionDocumentData.annualStatement(
                    AnnualStatementContent(summary, UUID.randomUUID(), "123", zero, LocalDate.of(2026, 1, 5)),
                ),
            )

    @Test
    fun `every template a render can ask for exists in cs and en, marked for legal review`() {
        assertThat(cases.keys).contains("pension-dps-key-information", "pension-dip-kid")
        cases.keys.forEach { base ->
            DocumentRendering.LANGUAGES.forEach { lang ->
                val body = source(DocumentRendering.templateCode(base, lang))
                assertThat(body).describedAs("$base-$lang").isNotNull()
                assertThat(
                    body,
                ).contains("LEGAL-REVIEW-REQUIRED").contains("{{#if ${DocumentRendering.LEGAL_REVIEW_KEY}}}")
            }
        }
    }

    @Test
    fun `templates reference only variables the adapter supplies`() {
        cases.forEach { (base, data) ->
            val supplied = data.keys + DocumentRendering.LEGAL_REVIEW_KEY
            DocumentRendering.LANGUAGES.forEach { lang ->
                val used = variable.findAll(source("$base-$lang")!!).map { it.groupValues[1] }.toSet()
                assertThat(supplied).describedAs("$base-$lang").containsAll(used)
            }
        }
    }
}
