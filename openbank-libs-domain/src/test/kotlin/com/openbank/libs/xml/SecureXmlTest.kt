// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.xml

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLStreamException
import javax.xml.transform.stream.StreamSource

class SecureXmlTest {
    @Test
    fun `concurrent output documents have independent ownership and contents`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val documents = executor.invokeAll(
                (1..128).map { index ->
                    Callable {
                        SecureXml.newDocument().apply {
                            assertThat(documentElement).isNull()
                            appendChild(createElementNS("urn:test:$index", "root").apply { textContent = "$index" })
                        }
                    }
                },
                5,
                TimeUnit.SECONDS,
            ).map { it.get(1, TimeUnit.SECONDS) }
            assertThat(documents.toSet()).hasSize(128)
            documents.forEachIndexed { index, document ->
                assertThat(document.documentElement.ownerDocument).isSameAs(document)
                assertThat(document.documentElement.namespaceURI).isEqualTo("urn:test:${index + 1}")
                assertThat(document.documentElement.textContent).isEqualTo("${index + 1}")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected input does not poison subsequent parsing or output construction`() {
        repeat(3) {
            assertThatThrownBy { SecureXml.parse("<!DOCTYPE r [<!ENTITY e 'bad'>]><r>&e;</r>") }
                .hasMessageContaining("DOCTYPE")
            val output = SecureXml.newDocument()
            output.appendChild(output.createElementNS("urn:recovered", "root"))
            assertThat(SecureXml.parse("<root xmlns='urn:recovered'>ok</root>").documentElement.textContent)
                .isEqualTo("ok")
            assertThat(output.documentElement.namespaceURI).isEqualTo("urn:recovered")
        }
    }

    private fun externalEntity(dir: Path): String {
        val secret = dir.resolve("secret.txt")
        Files.writeString(secret, MARKER)
        return "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY e SYSTEM \"${secret.toUri()}\">]><r>&e;</r>"
    }

    @Test
    fun `a plain document parses namespace-aware by default`() {
        val doc = SecureXml.parse("<a:r xmlns:a=\"urn:x\"><a:c>v</a:c></a:r>")
        assertThat(doc.documentElement.localName).isEqualTo("r")
        assertThat(doc.documentElement.namespaceURI).isEqualTo("urn:x")
        assertThat(doc.documentElement.textContent).isEqualTo("v")
    }

    @Test
    fun `DOM refuses a DOCTYPE and never reads the external entity`(@TempDir dir: Path) {
        assertThatThrownBy { SecureXml.parse(externalEntity(dir)) }
            .hasMessageContaining("DOCTYPE")
            .hasMessageNotContaining(MARKER)
    }

    @Test
    fun `SAX refuses a DOCTYPE and never reads the external entity`(@TempDir dir: Path) {
        val seen = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun characters(ch: CharArray, start: Int, length: Int) {
                seen.append(ch, start, length)
            }
        }
        assertThatThrownBy {
            SecureXml.saxParse(externalEntity(dir).byteInputStream(), handler, namespaceAware = false)
        }.hasMessageContaining("DOCTYPE")
        assertThat(seen.toString()).doesNotContain(MARKER)
    }

    @Test
    fun `StAX does not resolve an external entity`(@TempDir dir: Path) {
        val reader = SecureXml.xmlInputFactory().createXMLStreamReader(StringReader(externalEntity(dir)))
        val text = StringBuilder()
        // Depending on the StAX implementation the undeclared reference is either an error or
        // an unresolved ENTITY_REFERENCE event; either way the file content is never produced.
        runCatching {
            while (reader.hasNext()) {
                if (reader.next() == javax.xml.stream.XMLStreamConstants.CHARACTERS) text.append(reader.text)
            }
        }.onFailure { assertThat(it).isInstanceOf(XMLStreamException::class.java) }
        assertThat(text.toString()).doesNotContain(MARKER)
    }

    @Test
    fun `schema factory cannot fetch an external schema`(@TempDir dir: Path) {
        val imported = dir.resolve("imported.xsd")
        Files.writeString(
            imported,
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:i\"/>",
        )
        val xsd = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">" +
            "<xs:import namespace=\"urn:i\" schemaLocation=\"${imported.toUri()}\"/></xs:schema>"
        assertThatThrownBy { SecureXml.schemaFactory().newSchema(StreamSource(StringReader(xsd))) }
            .hasMessageContaining("accessExternalSchema")
    }

    @Test
    fun `transformer factory cannot load an external DTD`(@TempDir dir: Path) {
        val out = java.io.StringWriter()
        assertThatThrownBy {
            SecureXml.transformerFactory().newTransformer()
                .transform(
                    StreamSource(StringReader(externalEntity(dir))),
                    javax.xml.transform.stream.StreamResult(out),
                )
        }.isNotNull()
        assertThat(out.toString()).doesNotContain(MARKER)
    }

    companion object {
        private const val MARKER = "xxe-marker-must-never-be-read"
    }
}
