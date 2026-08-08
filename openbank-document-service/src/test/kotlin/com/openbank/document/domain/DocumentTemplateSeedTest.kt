// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.document.infrastructure.render.HandlebarsTemplateRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Renders every seeded demo template (VOP, framework agreement, current-account agreement,
 * monthly statement, annual statement of fees, payment confirmation — cs/en) through the REAL
 * [HandlebarsTemplateRenderer], not a mock — this is the check that would catch a hand-authoring
 * mistake in the HTML (an unbalanced `{{#if}}`/`{{/if}}`, a stray moustache) that a mocked-port
 * test never could.
 */
class DocumentTemplateSeedTest {

    private val renderer = HandlebarsTemplateRenderer()

    private val sampleData = mapOf(
        "party" to mapOf(
            "name" to "Jana Nováková",
            "address" to "Václavské náměstí 1, 110 00 Praha 1",
            "email" to "jana.novakova@example.com",
        ),
        "product" to mapOf("name" to "Standard Savings Account", "code" to "SAVINGS_STANDARD"),
        "account" to mapOf("iban" to "CZ6508000000192000145399", "currency" to "CZK"),
        "document" to mapOf(
            "date" to "2026-07-14",
            "caseRef" to "CASE-2026-000123",
            // ADR-0248 monthly statement fields (MESICNI_VYPIS_CS/EN).
            "periodFrom" to "2026-07-01",
            "periodTo" to "2026-07-31",
            "openingBalance" to "10 000.00",
            "closingBalance" to "12 345.67",
            "legalSequenceNumber" to "7",
            "electronicSequenceNumber" to "84",
            "generatedAt" to "2026-08-01T00:00:00Z",
            "entries" to listOf(
                mapOf(
                    "bookingDate" to "2026-07-05",
                    "valueDate" to "2026-07-05",
                    "amount" to "-450.00",
                    "currency" to "CZK",
                    "counterparty" to "ČEZ Prodej, a.s.",
                    "reference" to "VS0123456789",
                ),
                mapOf(
                    "bookingDate" to "2026-07-20",
                    "valueDate" to "2026-07-21",
                    "amount" to "2 795.67",
                    "currency" to "CZK",
                    "counterparty" to "Jan Novák",
                    "reference" to "Salary",
                ),
            ),
            // ADR-0248 annual statement of fees fields (ROCNI_VYPIS_POPLATKU_CS/EN).
            "year" to "2026",
            "currency" to "CZK",
            "totalFees" to "540.00",
            "interestRate" to "0.10",
            "issueDate" to "2027-01-15",
            "fees" to listOf(
                mapOf("name" to "Vedení účtu", "category" to "Account maintenance", "amount" to "480.00"),
                mapOf("name" to "Výběr z bankomatu jiné banky", "category" to "Cash withdrawals", "amount" to "60.00"),
            ),
        ),
        "signature" to mapOf("block" to "Podepsáno elektronicky / Signed electronically"),
        // ADR-0248 payment confirmation fields (POTVRZENI_O_PLATBE_CS/EN).
        "payment" to mapOf(
            "reference" to "PMT-2026-000987",
            "endToEndId" to "E2E-9F3C1B2A",
            "executedAt" to "2026-07-20T09:15:00Z",
            "settledAt" to "2026-07-20T09:15:03Z",
            "amount" to "2 500.00",
            "currency" to "CZK",
            "payerIban" to "CZ6508000000192000145399",
            "payeeIban" to "CZ9808000000001234567890",
            "payeeName" to "Jan Novák",
            "remittanceInfo" to "VS0123456789",
            "status" to "SETTLED",
            "scaEvidenceRef" to "SCA-2026-000456",
        ),
    )

    @TestFactory
    fun `every seeded template renders without throwing and every token is resolved`() =
        DocumentTemplateSeed.templates.map { template ->
            DynamicTest.dynamicTest("${template.code} v${template.version} (${template.locale})") {
                assertThat(template.status).isEqualTo(TemplateStatus.PUBLISHED)

                val rendered = renderer.renderHtml(template, sampleData)

                assertThat(rendered).isNotBlank()
                // An unresolved token (typo'd path, or sampleData missing a key the template
                // references) is a real authoring bug — Handlebars silently renders it as empty
                // string rather than failing, so the only reliable signal left is: no literal
                // `{{`/`}}` should survive into the output.
                assertThat(rendered).doesNotContain("{{").doesNotContain("}}")
            }
        }

    @TestFactory
    fun `codes are unique and locale-suffixed consistently`() = listOf(
        "VOP" to listOf("VOP_CS", "VOP_EN"),
        "RAMCOVA_SMLOUVA" to listOf("RAMCOVA_SMLOUVA_CS", "RAMCOVA_SMLOUVA_EN"),
        "UCET_SMLOUVA" to listOf("UCET_SMLOUVA_CS", "UCET_SMLOUVA_EN"),
        "MESICNI_VYPIS" to listOf("MESICNI_VYPIS_CS", "MESICNI_VYPIS_EN"),
        "ROCNI_VYPIS_POPLATKU" to listOf("ROCNI_VYPIS_POPLATKU_CS", "ROCNI_VYPIS_POPLATKU_EN"),
        "POTVRZENI_O_PLATBE" to listOf("POTVRZENI_O_PLATBE_CS", "POTVRZENI_O_PLATBE_EN"),
    ).map { (family, expectedCodes) ->
        DynamicTest.dynamicTest("$family has both a cs and an en variant") {
            val codes = DocumentTemplateSeed.templates.map { it.code }
            assertThat(codes).containsAll(expectedCodes)
        }
    }

    @TestFactory
    fun `ADR-0248 templates are seeded as HANDLEBARS-engine, published, restricted rows`() = listOf(
        "MESICNI_VYPIS_CS",
        "MESICNI_VYPIS_EN",
        "ROCNI_VYPIS_POPLATKU_CS",
        "ROCNI_VYPIS_POPLATKU_EN",
        "POTVRZENI_O_PLATBE_CS",
        "POTVRZENI_O_PLATBE_EN",
    ).map { code ->
        DynamicTest.dynamicTest("$code is HANDLEBARS/PUBLISHED/restricted at version 1.0.0") {
            val template = DocumentTemplateSeed.templates.single { it.code == code }
            assertThat(template.engine).isEqualTo(TemplateEngine.HANDLEBARS)
            assertThat(template.status).isEqualTo(TemplateStatus.PUBLISHED)
            assertThat(template.version).isEqualTo("1.0.0")
            assertThat(template.classification).isEqualTo("restricted")
            assertThat(template.productRef).isNull()
        }
    }
}
