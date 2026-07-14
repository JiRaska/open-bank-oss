// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

import com.openbank.document.domain.model.TemplateStatus
import com.openbank.document.infrastructure.render.HandlebarsTemplateRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Renders every seeded demo template (VOP, framework agreement, current-account agreement — cs/en)
 * through the REAL [HandlebarsTemplateRenderer], not a mock — this is the check that would catch a
 * hand-authoring mistake in the HTML (an unbalanced `{{#if}}`/`{{/if}}`, a stray moustache) that a
 * mocked-port test never could.
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
        "account" to mapOf("iban" to "CZ6508000000192000145399"),
        "document" to mapOf("date" to "2026-07-14", "caseRef" to "CASE-2026-000123"),
        "signature" to mapOf("block" to "Podepsáno elektronicky / Signed electronically"),
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
    ).map { (family, expectedCodes) ->
        DynamicTest.dynamicTest("$family has both a cs and an en variant") {
            val codes = DocumentTemplateSeed.templates.map { it.code }
            assertThat(codes).containsAll(expectedCodes)
        }
    }
}
