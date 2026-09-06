// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The builder tests cover the happy path. What is only exercised here is what the validator does
 * with input it must REJECT — untrusted wire XML that is malformed, entity-bearing, or simply not
 * the message the schema describes. A validator that answered [Iso20022ValidationResult.Valid] (or
 * threw past the caller) for any of these would be worse than none at all.
 */
class Iso20022ValidatorErrorPathTest {

    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)

    @Test
    fun `a document from a different ISO 20022 namespace is invalid, not merely unrecognised`() {
        val camtNamespaced =
            """<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08"><BkToCstmrDbtCdtNtfctn/></Document>"""
        val result = validator.validate(camtNamespaced)
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThat((result as Iso20022ValidationResult.Invalid).errors).isNotEmpty()
    }

    @Test
    fun `an unclosed tag is reported as invalid rather than throwing out of validate`() {
        val result = validator.validate("<Document><FIToFICstmrCdtTrf>")
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThat((result as Iso20022ValidationResult.Invalid).errors).isNotEmpty()
    }

    @Test
    fun `empty input is invalid`() {
        assertThat(validator.validate("")).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `a DOCTYPE declaration is refused outright - the primary XXE defence`() {
        val withDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE Document [ <!ENTITY xxe "gotcha"> ]>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
              <FIToFICstmrCdtTrf><GrpHdr><MsgId>&xxe;</MsgId></GrpHdr></FIToFICstmrCdtTrf>
            </Document>
        """.trimIndent()
        val result = validator.validate(withDoctype)
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        // The parse dies on the DOCTYPE itself, so the entity is never expanded into the result.
        assertThat((result as Iso20022ValidationResult.Invalid).errors.joinToString()).doesNotContain("gotcha")
    }

    @Test
    fun `reported errors carry the line and column so a builder bug is locatable`() {
        val result = validator.validate(
            """<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><NotAThing/></Document>""",
        )
        val errors = (result as Iso20022ValidationResult.Invalid).errors
        assertThat(errors).isNotEmpty()
        assertThat(errors).allSatisfy { assertThat(it).startsWith("line ") }
    }

    @Test
    fun `a missing schema resource fails loudly at construction - it is a packaging defect`() {
        assertThatThrownBy { Iso20022Validator.forSchema("pacs.999.001.99.xsd") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("schema not found")
    }

    @Test
    fun `one validator instance is reusable across calls and keeps no error state between them`() {
        val bad = validator.validate("<Document/>")
        assertThat(bad).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        val badAgain = validator.validate("<Document/>")
        assertThat((badAgain as Iso20022ValidationResult.Invalid).errors)
            .isEqualTo((bad as Iso20022ValidationResult.Invalid).errors)
    }
}
