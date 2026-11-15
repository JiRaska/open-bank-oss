// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.legalprocess

import com.openbank.legalprocess.isds.IsdsImport
import com.openbank.legalprocess.isds.IsdsSoapDownloads
import com.openbank.legalprocess.review.INBOX_ASSIGN
import com.openbank.legalprocess.review.INBOX_EVIDENCE
import com.openbank.legalprocess.review.INBOX_VIEW
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class LegalInboxDatabase : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgreSQLContainer("postgres:18.6-alpine")

    override fun start(): Map<String, String> {
        postgres.start()
        return mapOf(
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
            "quarkus.oidc.auth-server-url" to "http://localhost:1/realm",
            "quarkus.oidc.discovery-enabled" to "false",
            "quarkus.oidc.token.audience" to "synthetic-legal-inbox",
            "quarkus.oidc.jwks-path" to "/jwks",
            "quarkus.http.test-port" to "0",
        )
    }

    override fun stop() {
        postgres.stop()
    }
}

private const val ACCESSIBLE = "ROLE_LEGAL_MAILBOX_synthetic-inbox"

@QuarkusTest
@QuarkusTestResource(value = LegalInboxDatabase::class, restrictToAnnotatedClass = true)
class LegalInboxHttpTest {
    @Inject
    lateinit var dataSource: DataSource

    @TestHTTPResource("/api/v1/legal-inbox")
    lateinit var url: URI

    private val client = HttpClient.newHttpClient()

    @Test
    @TestSecurity(user = "operator-subject", roles = [INBOX_VIEW, INBOX_EVIDENCE, INBOX_ASSIGN, ACCESSIBLE])
    fun `import to HTTP queue original extraction and concurrent assignment persists audit`() {
        val source = cms("231")
        val id = import("synthetic-inbox", "231", source)
        val hidden = import("other-mailbox", "231", source)

        val list = request()
        assertEquals(200, list.statusCode())
        assertTrue(list.body().decodeToString().contains(id.toString()))
        assertFalse(list.body().decodeToString().contains(hidden.toString()))
        assertEquals(404, request("/$hidden").statusCode())
        assertEquals(404, request("/$hidden/originals/message").statusCode())

        assertArrayEquals(source, request("/$id/originals/message").body())
        assertArrayEquals(byteArrayOf(7, 9), request("/$id/originals/delivery").body())
        assertEquals(200, request("/$id/extract", "").statusCode())
        val draft = request("/$id").body().decodeToString()
        assertTrue(draft.contains("Zkušební návrh"))
        assertTrue(draft.contains("REF-231"))
        assertTrue(draft.contains("\"attachmentCount\":1"))

        val requests = listOf("ENFORCEMENT_CZ", "LEGAL_REVIEW_CZ").map { team ->
            client.sendAsync(assignment(id, team, 0), HttpResponse.BodyHandlers.discarding())
        }
        val outcomes = requests.map { it.get(20, TimeUnit.SECONDS).statusCode() }.sorted()
        assertEquals(listOf(200, 409), outcomes)
        assertTrue(request("/$id").body().decodeToString().contains("\"revision\":1"))
        assertEquals(
            listOf("DOWNLOAD_MESSAGE", "DOWNLOAD_DELIVERY", "EXTRACT", "READ_DRAFT", "ASSIGN", "READ_DRAFT"),
            audit(id),
        )
        assertEquals(emptyList<String>(), audit(hidden))
    }

    @Test
    @TestSecurity(user = "view-only-subject", roles = [INBOX_VIEW, ACCESSIBLE])
    fun `metadata grants neither evidence nor assignment`() {
        val id = import("synthetic-inbox", "232", byteArrayOf(4))
        assertEquals(200, request("/$id").statusCode())
        assertEquals(403, request("/$id/originals/message").statusCode())
        assertEquals(403, request("/$id/extract", "").statusCode())
        val response = client.send(assignment(id, "LEGAL_REVIEW_CZ", 0), HttpResponse.BodyHandlers.discarding())
        assertEquals(403, response.statusCode())
        assertEquals(emptyList<String>(), audit(id, "view-only-subject"))
    }

    @Test
    @TestSecurity(user = "unscoped-subject", roles = [INBOX_VIEW, INBOX_EVIDENCE])
    fun `mailbox grant is required even with evidence capability`() {
        assertEquals(403, request().statusCode())
    }

    @Test
    @TestSecurity(user = "operator-subject", roles = [INBOX_VIEW, INBOX_EVIDENCE, ACCESSIBLE])
    fun `unsupported envelope enters manual review and repeated extraction is a no-op`() {
        val id = import("synthetic-inbox", "233", byteArrayOf(4))
        assertEquals(200, request("/$id/extract", "").statusCode())
        assertEquals(200, request("/$id/extract", "").statusCode())
        assertTrue(request("/$id").body().decodeToString().contains("MANUAL_REVIEW"))
        assertEquals(listOf("EXTRACT", "READ_DRAFT"), audit(id))
    }

    private fun import(mailbox: String, id: String, source: ByteArray): UUID {
        val client = IsdsSoapDownloads.usingTransport { endpoint, _ ->
            val (operation, bytes) = if (endpoint == "dz") {
                "SignedMessageDownload" to source
            } else {
                "GetSignedDeliveryInfo" to byteArrayOf(7, 9)
            }
            """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:v="http://isds.czechpoint.cz/v20">
                  <s:Body><v:${operation}Response><v:dmSignature>${Base64.getEncoder().encodeToString(
                bytes,
            )}</v:dmSignature>
                  <v:dmStatus><v:dmStatusCode>0000</v:dmStatusCode></v:dmStatus></v:${operation}Response></s:Body>
                </s:Envelope>
            """.trimIndent().toByteArray()
        }
        val input = IsdsImport(mailbox, client, PostgresLegalDeliveryInbox(dataSource)).importMessage(id)
        return (input as LegalDeliveryOutcome.Received).receiptId
    }

    private fun request(path: String = "", body: String? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url.toString() + path))
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun assignment(id: UUID, team: String, revision: Int) = HttpRequest
        .newBuilder(URI.create("$url/$id/assignment"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"team":"$team","expectedRevision":$revision}"""))
        .build()

    private fun audit(id: UUID, subject: String = "operator-subject"): List<String> = dataSource.connection.use { db ->
        db.prepareStatement(
            "SELECT action FROM legal_inbox_audit WHERE receipt_id = ? AND actor_subject = ? ORDER BY id",
        ).use {
            it.setObject(1, id)
            it.setString(2, subject)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun cms(id: String): ByteArray {
        val xml = """
            <MessageDownloadResponse xmlns="http://isds.czebox.cz/v20/message">
              <dmReturnedMessage><dmDm><dmID>$id</dmID><dmAnnotation>Zkušební návrh</dmAnnotation>
              <dmSenderRefNumber>REF-$id</dmSenderRefNumber><dmFiles><dmFile/></dmFiles>
              </dmDm></dmReturnedMessage>
            </MessageDownloadResponse>
        """.trimIndent()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val dn = X500Name("CN=Inbox Test")
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(key.private)
        val now = Instant.now()
        val certificate = JcaX509v3CertificateBuilder(
            dn,
            BigInteger.ONE,
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(600)),
            dn,
            key.public,
        ).build(signer)
        val info = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().build(),
        ).build(signer, certificate)
        return CMSSignedDataGenerator().apply {
            addCertificate(certificate)
            addSignerInfoGenerator(info)
        }.generate(CMSProcessableByteArray(xml.toByteArray()), true).encoded
    }
}
