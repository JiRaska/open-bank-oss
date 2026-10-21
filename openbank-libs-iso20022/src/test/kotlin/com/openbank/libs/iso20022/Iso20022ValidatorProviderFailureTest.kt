// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import javax.xml.transform.Result
import javax.xml.transform.Source
import javax.xml.validation.Schema
import javax.xml.validation.Validator
import javax.xml.validation.ValidatorHandler

class Iso20022ValidatorProviderFailureTest {
    @Test
    fun `a SAX rejection without parse coordinates is an invalid result`() {
        val schema = object : Schema() {
            override fun newValidator(): Validator = object : Validator() {
                override fun validate(source: Source, result: Result?) {
                    // The JAXP contract allows SAXException, not just SAXParseException.
                    throw SAXException("provider rejected the document")
                }

                override fun reset() = Unit
                override fun setProperty(name: String, value: Any?) = Unit
                override fun setErrorHandler(handler: ErrorHandler?) = Unit
                override fun getErrorHandler(): ErrorHandler? = null
                override fun setResourceResolver(resolver: LSResourceResolver?) = Unit
                override fun getResourceResolver(): LSResourceResolver? = null
            }

            override fun newValidatorHandler(): ValidatorHandler = error("handler not used by this test")
        }
        val result = Iso20022Validator(schema).validate("<root/>")
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        assertThat((result as Iso20022ValidationResult.Invalid).errors)
            .contains("provider rejected the document")
    }
}
