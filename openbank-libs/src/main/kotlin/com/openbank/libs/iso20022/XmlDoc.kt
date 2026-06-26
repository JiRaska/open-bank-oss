// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Tiny namespace-aware DOM builder shared by every ISO 20022 message builder
 * ([Pacs008Builder], [Pacs002Builder], [Camt054Builder]).
 *
 * Wraps a single [Document] bound to one ISO 20022 target namespace and offers the two
 * operations every builder needs — create a namespaced child, and create a namespaced child
 * with text — plus [serialize]. Built on the JDK's DOM/JAXP only, so the library carries no
 * XML-binding dependency. The parser is hardened against XXE (no DTD, no external entities);
 * input is trusted but the builders also parse rail-supplied content via [Iso20022Reader].
 */
internal class XmlDoc private constructor(private val namespace: String, val document: Document) {
    /** Creates the single top-level `Document` element in the namespace and returns it. */
    fun root(name: String): Element = document.createElementNS(namespace, name).also { document.appendChild(it) }

    /** Creates a namespaced child element, appends it to [parent], and returns it. */
    fun child(parent: Element, name: String): Element =
        document.createElementNS(namespace, name).also { parent.appendChild(it) }

    /** Creates a namespaced child element with [value] as text content; returns the element. */
    fun text(parent: Element, name: String, value: String): Element =
        child(parent, name).also { it.textContent = value }

    /** Serialises the document to indented, UTF-8 XML with an XML declaration. */
    fun serialize(): String {
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    companion object {
        fun create(namespace: String): XmlDoc {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            return XmlDoc(namespace, factory.newDocumentBuilder().newDocument())
        }
    }
}
