// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.legalprocess.isds

import org.w3c.dom.Element
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** ISDS ws1 ordinary-message operations. CAdES validation belongs to the subsequent review stage. */
class IsdsSoapDownloads private constructor(private val transport: (String, String) -> ByteArray) :
    IsdsSignedDownloads {
    override fun message(messageId: String): ByteArray = download("SignedMessageDownload", "dz", messageId)

    override fun deliveryInfo(messageId: String): ByteArray = download("GetSignedDeliveryInfo", "dx", messageId)

    internal fun received(from: Instant, to: Instant, limit: Int): List<String> {
        require(from < to && limit > 0)
        val operation = "GetListOfReceivedMessages"
        val request = """
            <s:Envelope xmlns:s="$SOAP" xmlns:v="$ISDS" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <s:Body><v:$operation>
                <v:dmFromTime>$from</v:dmFromTime><v:dmToTime>$to</v:dmToTime>
                <v:dmRecipientOrgUnitNum xsi:nil="true"/><v:dmStatusFilter>-1</v:dmStatusFilter>
                <v:dmOffset>1</v:dmOffset><v:dmLimit>$limit</v:dmLimit>
              </v:$operation></s:Body>
            </s:Envelope>
        """.trimIndent()
        val records = response(transport("dx", request), operation).child(ISDS, "dmRecords")
        return records.children(ISDS, "dmRecord").map { record ->
            record.child(ISDS, "dmID").textContent.trim().also { check(ISDS_MESSAGE_ID.matches(it)) }
        }.also { check(it.size <= limit) }
    }

    private fun download(operation: String, endpoint: String, id: String): ByteArray {
        require(ISDS_MESSAGE_ID.matches(id)) { "Invalid ISDS message ID" }
        val request = """
            <s:Envelope xmlns:s="$SOAP" xmlns:v="$ISDS">
              <s:Body><v:$operation><v:dmID>$id</v:dmID></v:$operation></s:Body>
            </s:Envelope>
        """.trimIndent()
        return parseSignedResponse(transport(endpoint, request), operation)
    }

    enum class Environment(internal val base: String) {
        PUBLIC_TEST("https://ws1.czebox.cz"),
        PRODUCTION("https://ws1.mojedatovaschranka.cz"),
    }

    companion object {
        private const val SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val ISDS = "http://isds.czechpoint.cz/v20"
        private val connectTimeout = Duration.ofSeconds(10)
        private val requestTimeout = Duration.ofSeconds(30)
        private const val HTTP_OK = 200
        private const val MAX_RESPONSE_BYTES = 40 * 1024 * 1024

        /** Use a caller-provided TLS context and a secret from the runtime secret store. */
        fun authenticated(
            environment: Environment,
            base64Credentials: () -> String,
            sslContext: SSLContext = SSLContext.getDefault(),
        ): IsdsSoapDownloads {
            val client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build()
            return IsdsSoapDownloads { endpoint, xml ->
                val request = HttpRequest.newBuilder(URI.create("${environment.base}/DS/$endpoint"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Basic ${base64Credentials()}")
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"\"")
                    .POST(HttpRequest.BodyPublishers.ofString(xml))
                    .build()
                val handler = HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(),
                    MAX_RESPONSE_BYTES.toLong(),
                )
                val response = client.send(request, handler)
                check(response.statusCode() == HTTP_OK) { "ISDS HTTP failure (${response.statusCode()})" }
                response.body()
            }
        }

        internal fun usingTransport(transport: (String, String) -> ByteArray) = IsdsSoapDownloads(transport)

        private fun response(xml: ByteArray, operation: String): Element {
            require(xml.size <= MAX_RESPONSE_BYTES) { "ISDS response exceeds import limit" }
            val factory = DocumentBuilderFactory.newNSInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val envelope = factory.newDocumentBuilder().parse(xml.inputStream()).documentElement
            check(envelope.namespaceURI == SOAP && envelope.localName == "Envelope")
            val body = envelope.child(SOAP, "Body")
            val response = body.child(ISDS, "${operation}Response")
            val status = response.child(ISDS, "dmStatus").child(ISDS, "dmStatusCode").textContent.trim()
            check(status == "0000") { "ISDS operation did not return success" }
            return response
        }

        private fun parseSignedResponse(xml: ByteArray, operation: String): ByteArray {
            val raw = response(xml, operation).child(ISDS, "dmSignature").textContent.filterNot { it in " \r\n\t" }
            val original = Base64.getDecoder().decode(raw)
            require(original.isNotEmpty() && original.size <= MAX_SIGNED_EVIDENCE_BYTES) {
                "Invalid ISDS signed original size"
            }
            return original
        }

        private fun Element.child(namespace: String, name: String): Element =
            checkNotNull(children(namespace, name).singleOrNull()) {
                "Missing or ambiguous ISDS response element $name"
            }

        private fun Element.children(namespace: String, name: String): List<Element> =
            (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
                .filter { it.namespaceURI == namespace && it.localName == name }
    }
}
