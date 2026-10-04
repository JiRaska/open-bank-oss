// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.xml

import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.transform.TransformerFactory
import javax.xml.validation.SchemaFactory

/**
 * The ONE place in the fleet that instantiates a JAXP XML factory.
 *
 * Every factory handed out here is locked down the same way, so a parse site cannot forget a
 * setting: no DOCTYPE at all (the primary defence — no DTD means no entity can be declared,
 * external or internal), no external general/parameter entities, no external DTD loading, no
 * XInclude, no entity-reference expansion, `FEATURE_SECURE_PROCESSING` on (JDK entity/size
 * limits), and every `ACCESS_EXTERNAL_*` property set to the empty string (no protocol allowed).
 * Builders additionally carry an [EntityResolver] that refuses, so even a misconfigured JAXP
 * implementation cannot fetch anything.
 *
 * The `xml-factory-hardened` gate fails on a raw `*Factory.newInstance()` anywhere else in
 * `src/main`, so new code has to come through here. JDK only — no framework (ADR-0002/0122).
 */
object SecureXml {
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"

    private val REFUSING_RESOLVER = EntityResolver { _, systemId ->
        throw SAXException("external entity resolution is disabled ($systemId)")
    }

    /** A DOM factory with every XXE vector closed. */
    @JvmStatic
    @JvmOverloads
    fun documentBuilderFactory(namespaceAware: Boolean = true): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature(DISALLOW_DOCTYPE, true)
        factory.setFeature(EXTERNAL_GENERAL, false)
        factory.setFeature(EXTERNAL_PARAMETER, false)
        factory.setFeature(LOAD_EXTERNAL_DTD, false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory
    }

    /** A DOM builder from [documentBuilderFactory] that also refuses any entity resolution. */
    @JvmStatic
    @JvmOverloads
    fun documentBuilder(namespaceAware: Boolean = true): DocumentBuilder {
        val builder = documentBuilderFactory(namespaceAware).newDocumentBuilder()
        builder.setEntityResolver(REFUSING_RESOLVER)
        return builder
    }

    /**
     * Parses untrusted [xml]. A DOCTYPE, an entity declaration or malformed input surfaces as a
     * [SAXException] (callers map it to their own typed parse error).
     *
     * The hardening is spelled out INLINE, as literal calls on the same factory right before the
     * parse, rather than delegated to [documentBuilderFactory]: static analysers (CodeQL java/xxe)
     * only credit configuration they can see on the factory in the same data flow as the parse.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(xml: InputStream, namespaceAware: Boolean = true): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver(REFUSING_RESOLVER)
        return builder.parse(xml)
    }

    /**
     * Streams untrusted [xml] into [handler] with SAX. Same inline-hardening rule as [parse]: the
     * factory is configured and the parse happens in this one function.
     */
    @JvmStatic
    @JvmOverloads
    fun saxParse(xml: InputStream, handler: DefaultHandler, namespaceAware: Boolean = true) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        val parser = factory.newSAXParser()
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        parser.parse(xml, handler)
    }

    /** [parse] over bytes. */
    @JvmStatic
    @JvmOverloads
    fun parse(xml: ByteArray, namespaceAware: Boolean = true): Document =
        parse(ByteArrayInputStream(xml), namespaceAware)

    /** [parse] over a string, encoded as UTF-8. */
    @JvmStatic
    @JvmOverloads
    fun parse(xml: String, namespaceAware: Boolean = true): Document =
        parse(xml.toByteArray(Charsets.UTF_8), namespaceAware)

    /** A W3C XML Schema factory that cannot resolve external DTDs or schemas. */
    @JvmStatic
    fun schemaFactory(): SchemaFactory {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory
    }

    /** A transformer factory that cannot load external DTDs or stylesheets. */
    @JvmStatic
    fun transformerFactory(): TransformerFactory {
        val factory = TransformerFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        return factory
    }

    /** A StAX factory with DTD support and external entities disabled. */
    @JvmStatic
    fun xmlInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory
    }
}
