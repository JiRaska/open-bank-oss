// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.legalprocess.review

import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSSignedData
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal enum class ExtractionStatus { DRAFT, MANUAL_REVIEW }

internal data class EnvelopeDraft(
    val status: ExtractionStatus,
    val subject: String? = null,
    val senderReference: String? = null,
    val attachmentCount: Int? = null,
)

/** Reads envelope metadata from stored CMS. The result is a draft pending independent verification. */
internal object IsdsEnvelopeExtraction {
    private const val TYPES = "http://isds.czechpoint.cz/v20"
    private const val NAMESPACE = "http://isds.czebox.cz/v20/message"
    private const val SUBJECT_LIMIT = 256
    private const val REFERENCE_LIMIT = 128

    @Suppress("SwallowedException")
    fun read(original: ByteArray, deliveryId: String): EnvelopeDraft {
        val fallback = EnvelopeDraft(ExtractionStatus.MANUAL_REVIEW)
        return try {
            val signed = CMSSignedData(original)
            val bytes = signed.signedContent?.content as? ByteArray ?: return fallback
            if (signed.signerInfos.size() == 0 || bytes.size > original.size) return fallback
            parseEnvelope(bytes, deliveryId) ?: fallback
        } catch (_: CMSException) {
            fallback
        } catch (_: SAXException) {
            fallback
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    private fun parseEnvelope(bytes: ByteArray, deliveryId: String): EnvelopeDraft? {
        val document = DocumentBuilderFactory.newNSInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }.parse(bytes.inputStream())
        val root = document.documentElement
        if (root.namespaceURI != NAMESPACE || root.localName != "MessageDownloadResponse") return null
        val returned = root.child("dmReturnedMessage") ?: return null
        val envelope = returned.child("dmDm") ?: return null
        if (envelope.child("dmID")?.textContent?.trim() != deliveryId) return null
        return EnvelopeDraft(
            ExtractionStatus.DRAFT,
            envelope.child("dmAnnotation")?.textContent?.trim()?.take(SUBJECT_LIMIT),
            envelope.child("dmSenderRefNumber")?.textContent?.trim()?.take(REFERENCE_LIMIT),
            envelope.child("dmFiles")?.children("dmFile")?.size ?: 0,
        )
    }

    private fun Element.child(name: String): Element? = children(name).singleOrNull()

    private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { (it.namespaceURI == NAMESPACE || it.namespaceURI == TYPES) && it.localName == name }
}
