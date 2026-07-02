// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * Validates an ISO 20022 message (as produced by the rail builders) against its vendored XSD.
 *
 * ADR-0104: every outbound scheme message MUST be marshalled and validated against the pinned
 * schema before it leaves the rail. A message that does not validate is a bug in the builder,
 * not a business reject — callers treat [validate] failure as a fatal precondition, never as a
 * scheme rejection (those arrive later as a `pacs.002`).
 *
 * Uses only the JDK's built-in JAXP ([SchemaFactory] / W3C XML Schema), so the library carries
 * no XML-binding dependency. Schemas are loaded once from the classpath and cached — [Schema] is
 * thread-safe, so a single validator instance is safe to share.
 *
 * [validate] parses XML that arrives over the wire, so both the [SchemaFactory] ([forSchema]) and
 * the per-call [javax.xml.validation.Validator] ([validate]) have external DTD/schema access
 * disabled — XXE-hardened, mirroring [Pacs008Reader]/[Pacs004Reader].
 */
class Iso20022Validator(private val schema: Schema) {
    /**
     * Validates [xml] against the configured schema.
     *
     * @return [Iso20022ValidationResult.Valid] when the document conforms, otherwise
     *   [Iso20022ValidationResult.Invalid] carrying every error/fatal the parser reported
     *   (warnings are not failures).
     */
    fun validate(xml: String): Iso20022ValidationResult {
        val errors = mutableListOf<String>()
        val validator = schema.newValidator()
        // XXE hardening: xml is untrusted wire input. Disabling external DTD/schema access on the
        // Validator itself (not just the SchemaFactory that built schema) is the belt-and-suspenders
        // fix — it is the object CodeQL's java/xxe sink actually flags (validate() below).
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.errorHandler = object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) {
                errors += format(exception)
            }
            override fun fatalError(exception: SAXParseException) {
                errors += format(exception)
            }
        }
        return try {
            validator.validate(StreamSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
            if (errors.isEmpty()) {
                Iso20022ValidationResult.Valid
            } else {
                Iso20022ValidationResult.Invalid(errors.toList())
            }
        } catch (e: SAXParseException) {
            // A fatal parse error can be thrown rather than reported when no handler swallows it.
            Iso20022ValidationResult.Invalid(errors + format(e))
        }
    }

    private fun format(e: SAXParseException): String = "line ${e.lineNumber}:${e.columnNumber} ${e.message}"

    companion object {
        /**
         * Builds a validator for the schema vendored at
         * `iso20022/schemas/<schemaResource>` on the classpath (e.g. `pacs.008.001.08.xsd`).
         *
         * @throws IllegalStateException if the schema resource is missing or not a valid XSD —
         *   both are build/packaging defects that must fail loudly at startup, never silently.
         */
        fun forSchema(schemaResource: String): Iso20022Validator {
            val path = "iso20022/schemas/$schemaResource"
            val url = Iso20022Validator::class.java.classLoader.getResource(path)
                ?: error("ISO 20022 schema not found on classpath: $path")
            val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            // XXE hardening: no vendored schema uses xs:import/xs:include, so external
            // resolution is never legitimately needed — disable it outright.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val schema = try {
                factory.newSchema(url)
            } catch (e: org.xml.sax.SAXException) {
                throw IllegalStateException("Invalid ISO 20022 schema $path: ${e.message}", e)
            }
            return Iso20022Validator(schema)
        }
    }
}

/** Outcome of validating a message against its ISO 20022 schema. */
sealed interface Iso20022ValidationResult {
    /** The document conforms to the schema. */
    data object Valid : Iso20022ValidationResult

    /** The document does not conform; [errors] holds every schema violation reported. */
    data class Invalid(val errors: List<String>) : Iso20022ValidationResult
}
