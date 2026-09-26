// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The business agreement over real HTTP, a real database and the service's real adapters: the
 * Handlebars → PDF render goes through `HttpPdfRenderAdapter`, the fee schedule through
 * `ProductCatalogAdapter`, every signature through `ScaVerificationAdapter`, the per-signer client
 * signature and the final PAdES seal through the production signing adapters. Only the three
 * REMOTE services those adapters call (renderer sidecar, product-catalog, sca-service) are played
 * by a local HTTP server, because none of them runs inside this JVM.
 *
 * Also proves item 4 of the business-onboarding spec: a signer whose partyRef is a HUMAN signs a
 * document whose partyRef is the ENTITY — document-service never compares the two.
 */
@QuarkusTest
@TestProfile(BusinessAgreementIT.RemoteStubsProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_documents_it")],
)
@QuarkusTestResource(BusinessAgreementIT.InMemoryKafkaResource::class)
class BusinessAgreementIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    class RemoteStubsProfile : QuarkusTestProfile {
        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(RemoteStubs::class.java))
    }

    private val caseId = UUID.randomUUID()
    private val entityId = UUID.randomUUID()
    private val alice = UUID.randomUUID().toString()
    private val bob = UUID.randomUUID().toString()

    private fun body(signingRule: String = "Za společnost jednají dva jednatelé společně.") = """
        {
          "caseId": "$caseId", "entityPartyId": "$entityId", "lang": "cs",
          "entity": {"name": "Stavby Horák s.r.o.", "ico": "27074358",
                     "seat": "Jindřišská 16, 110 00 Praha 1", "legalForm": "společnost s ručením omezeným"},
          "representatives": [{"partyRef": "$alice", "name": "Petr Horák", "role": "jednatel"},
                              {"partyRef": null, "name": "Eva Horáková", "role": "jednatelka"}],
          "signers": [{"partyRef": "$alice", "name": "Petr Horák", "role": "jednatel"},
                      {"partyRef": "$bob", "name": "Eva Horáková", "role": "jednatelka"}],
          "signingRule": "$signingRule",
          "product": {"code": "prod-004", "name": "Business Current Account", "currency": "EUR"}
        }
    """.trimIndent()

    private fun ensure(json: String) = given().contentType(ContentType.JSON).body(json)
        .post("/api/v1/business-agreements")

    private fun sign(ceremonyId: String, partyRef: String) = given().contentType(ContentType.JSON)
        .body("""{"partyRef":"$partyRef","decision":"SIGNED","evidenceRef":"${UUID.randomUUID()}"}""")
        .post("/api/v1/signature-ceremonies/$ceremonyId/decisions")

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `render, sign by two humans for the entity, seal, then refuse replacement`() {
        val created = ensure(body()).then().statusCode(200).extract().jsonPath()
        val ceremonyId = created.getString("ceremonyId")
        val documentId = created.getString("documentId")
        val sha256 = created.getString("sha256")
        assertThat(created.getString("templateCode")).isEqualTo("RAMCOVA_SMLOUVA_PO_CS")
        assertThat(created.getString("ceremonyStatus")).isEqualTo("PENDING")
        assertThat(created.getList<String>("signers.partyRef")).containsExactly(alice, bob)
        assertThat(created.getList<String>("disclosures.code"))
            .containsExactly("VOP_CS", "SAZEBNIK_PO_CS", "PREDSMLUVNI_INFORMACE_PO_CS", "INFORMACE_POJISTENI_VKLADU_CS")
        assertThat(created.getString("sealedSha256")).isNull()

        // Real render path: the renderer received the merged HTML, and the stored bytes are its PDF.
        val agreementHtml = RemoteStubs.renderedHtml.single {
            it.contains("<h1>Rámcová smlouva o poskytování platebních služeb a o vedení")
        }
        assertThat(agreementHtml).contains("Stavby Horák s.r.o.", "27074358", "Petr Horák", "Eva Horáková")
        val feeHtml = RemoteStubs.renderedHtml.single { it.contains("<h1>Sazebník poplatků pro podnikatele") }
        assertThat(feeHtml).contains("Vedení účtu", "19,99 EUR", "Vklad hotovosti").doesNotContain("Monthly Fee")
        val content = given().get("/api/v1/documents/$documentId/content").then().statusCode(200)
            .extract().asByteArray()
        assertThat(sha256Of(content)).isEqualTo(sha256)

        // Idempotent.
        val again = ensure(body()).then().statusCode(200).extract().jsonPath()
        assertThat(again.getString("documentId")).isEqualTo(documentId)
        assertThat(again.getList<String>("disclosures.documentId"))
            .isEqualTo(created.getList<String>("disclosures.documentId"))

        // Every stored document belongs to the entity and the case.
        assertThat(documentRows()).hasSize(5).allSatisfy { (party, case) ->
            assertThat(party).isEqualTo(entityId.toString())
            assertThat(case).isEqualTo(caseId.toString())
        }

        // Humans sign a document owned by the entity; SCA is checked against the agreement sha256.
        // Joint signers sign in any order: bob (second in the request) signs first.
        sign(ceremonyId, bob).then().statusCode(200)
        assertThat(RemoteStubs.consumed.last()).contains(bob, sha256, ceremonyId)

        ensure(body(signingRule = "Za společnost jedná každý jednatel samostatně.")).then().statusCode(409)

        given().get("/api/v1/business-agreements/$caseId?lang=cs").then().statusCode(200)
            .body("ceremonyStatus", org.hamcrest.Matchers.equalTo("PARTIALLY_SIGNED"))
        sign(ceremonyId, alice).then().statusCode(200)
        val done = given().get("/api/v1/business-agreements/$caseId?lang=cs").then().statusCode(200)
            .extract().jsonPath()
        assertThat(done.getString("ceremonyStatus")).isEqualTo("COMPLETED")
        assertThat(done.getList<String>("signers.status")).containsExactly("SIGNED", "SIGNED")
        assertThat(done.getString("sha256")).isEqualTo(sha256)
        val sealed = done.getString("sealedSha256")
        assertThat(sealed).isNotNull().isNotEqualTo(sha256)
        val sealedContent = given().get("/api/v1/documents/$documentId/content").then().statusCode(200)
            .extract().asByteArray()
        assertThat(sha256Of(sealedContent)).isEqualTo(sealed)

        ensure(body(signingRule = "changed after signing")).then().statusCode(409)
        ensure(body()).then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `unknown case is 404 and an invalid request is 400`() {
        given().get("/api/v1/business-agreements/${UUID.randomUUID()}").then().statusCode(404)
        ensure(body().replace("\"lang\": \"cs\"", "\"lang\": \"de\"")).then().statusCode(400)
        ensure("""{"caseId":"$caseId"}""").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "customer", roles = ["ROLE_CUSTOMER"])
    fun `a customer token is refused at the role gate`() {
        ensure(body()).then().statusCode(403)
        given().get("/api/v1/business-agreements/$caseId").then().statusCode(403)
    }

    @Test
    fun `an anonymous caller is refused`() {
        ensure(body()).then().statusCode(401)
    }

    private fun documentRows(): List<Pair<String?, String?>> {
        val config = ConfigProvider.getConfig()
        val connection = DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
        return connection.use { c ->
            val st = c.prepareStatement(
                "select party_ref, case_ref from documents where case_ref = ? and status <> 'ARCHIVED'",
            )
            st.setString(1, caseId.toString())
            val rs = st.executeQuery()
            generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
        }
    }

    private fun sha256Of(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Plays the three remote services. The renderer answers with a genuine one-page PDF (the
     * signing and sealing adapters parse and rewrite it), titled with the HTML's digest so two
     * different renders never share bytes.
     */
    class RemoteStubs : QuarkusTestResourceLifecycleManager {
        companion object {
            val renderedHtml = CopyOnWriteArrayList<String>()
            val consumed = CopyOnWriteArrayList<String>()
        }

        private lateinit var http: HttpServer

        override fun start(): Map<String, String> {
            http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/render") { ex ->
                val html = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                renderedHtml += html
                respond(ex, 200, "application/pdf", pdf(html))
            }
            http.createContext("/api/v1/products") { ex ->
                respond(ex, 200, "application/json", CATALOG_PRODUCT.toByteArray())
            }
            http.createContext("/api/v1/sca/challenges") { ex ->
                val request = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                consumed += request
                val challengeId = ex.requestURI.path.split('/')[5]
                val partyId = Regex("\"partyId\"\\s*:\\s*\"([^\"]+)\"").find(request)!!.groupValues[1]
                respond(
                    ex,
                    200,
                    "application/json",
                    """{"id":"$challengeId","partyId":"$partyId","challengeType":"DOCUMENT_SIGNING","status":"CONSUMED"}"""
                        .toByteArray(),
                )
            }
            http.createContext("/token") { ex ->
                respond(
                    ex,
                    200,
                    "application/json",
                    """{"access_token":"t","token_type":"Bearer","expires_in":300}""".toByteArray(),
                )
            }
            http.executor = Executors.newFixedThreadPool(2)
            http.start()
            val base = "http://127.0.0.1:${http.address.port}"
            return mapOf(
                "openbank.render.profile" to "weasyprint",
                "openbank.render.weasyprint-url" to base,
                "quarkus.rest-client.product-catalog-api.url" to base,
                "quarkus.rest-client.sca-service.url" to base,
                "quarkus.oidc-client.auth-server-url" to base,
                "quarkus.oidc-client.discovery-enabled" to "false",
                "quarkus.oidc-client.token-path" to "/token",
            )
        }

        override fun stop() {
            http.stop(0)
        }

        private fun respond(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        private fun pdf(html: String): ByteArray = PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentInformation.title = MessageDigest.getInstance("SHA-256")
                .digest(html.toByteArray()).joinToString("") { "%02x".format(it) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private companion object {
        val CATALOG_PRODUCT = """
            {"id":"prod-004","code":"CURRENT_BUSINESS","name":"Business Current Account","currency":"EUR",
             "termsAndConditions":[],
             "fees":[{"name":"Monthly Fee","type":"MONTHLY","amount":19.99,"currency":"EUR","frequency":"MONTHLY"},
                     {"name":"Cash Deposit","type":"TRANSACTION","amount":0.5,"currency":"EUR",
                      "frequency":"PERCENTAGE","description":"0.5% of deposit amount"}]}
        """.trimIndent()
    }
}
