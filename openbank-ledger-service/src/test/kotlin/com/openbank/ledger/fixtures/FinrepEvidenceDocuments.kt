// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

internal object FinrepEvidenceDocuments {
    fun json(path: Path): JsonNode = try {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files.size(path) <= 1_048_576)
        ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readTree(Files.readAllBytes(path)).let { requireNotNull(it) { "Hosted evidence JSON is invalid" } }
    } catch (_: IOException) {
        throw IllegalArgumentException("Hosted evidence JSON is invalid")
    }

    private fun xmlBytes(path: Path): ByteArray = try {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files.size(path) <= 4_194_304)
        Files.readAllBytes(path)
    } catch (_: IOException) {
        throw IllegalArgumentException("Hosted evidence XML is invalid")
    }

    fun xml(path: Path): Document {
        val bytes = xmlBytes(path)
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()
        parser.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException): Unit = throw exception
            override fun fatalError(exception: SAXParseException): Unit = throw exception
        })
        return try {
            bytes.inputStream().use { parser.parse(it) }
        } catch (_: SAXException) {
            throw IllegalArgumentException("Hosted evidence XML is invalid")
        } catch (_: IOException) {
            throw IllegalArgumentException("Hosted evidence XML is invalid")
        }
    }
}
