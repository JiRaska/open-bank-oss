// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.e2e

import com.openbank.document.it.PostgresRedisTestResource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * End-to-end journeys of the document rail over the real HTTP surface against real Postgres +
 * Valkey. The only thing standing outside this JVM is the weasyprint renderer sidecar, played by
 * a localhost stub exactly as `BusinessAgreementIT` does — and the stub records the HTML it was
 * asked to render, so the test asserts the template/data merge reached the render boundary.
 *
 *  - Template lifecycle: DRAFT → publish → PUBLISHED → render → document with sha256, size and
 *    content type; the metadata, party browse (with `X-Total-Count`) and the octet-stream content
 *    download all read the same stored artifact back.
 *  - Only PUBLISHED templates render: a DRAFT and a RETIRED template both refuse 422 ("No
 *    published template"), not a 500 from the render boundary.
 *  - The browse guards: a blank `partyRef` and a negative `page` are 400s, and `ROLE_VIEWER` —
 *    admitted on most fleet read surfaces — is refused 403 here (this resource serves
 *    ROLE_API/OPERATOR/ADMIN only).
 *
 * `@Authorize` runs against the test classpath `MockAuthzProducer` (allows unless
 * `test.authz.allow=false`); the deployed `AUTHZ_ENFORCE:true` default is untouched.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(DocumentLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(DocumentLifecycleE2E.RenderStub::class)
class DocumentLifecycleE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    class RenderStub : QuarkusTestResourceLifecycleManager {
        companion object {
            val renderedHtml = CopyOnWriteArrayList<String>()
        }

        private lateinit var http: HttpServer

        override fun start(): Map<String, String> {
            http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/render") { ex ->
                val html = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                renderedHtml += html
                respond(ex, 200, "application/pdf", pdf())
            }
            http.executor = Executors.newFixedThreadPool(2)
            http.start()
            return mapOf(
                "openbank.render.profile" to "weasyprint",
                "openbank.render.weasyprint-url" to "http://127.0.0.1:${http.address.port}",
            )
        }

        override fun stop() = http.stop(0)

        private fun respond(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        private fun pdf(): ByteArray = PDDocument().use { doc ->
            doc.addPage(PDPage())
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a template is published rendered and read back end to end`() {
        val code = "E2E_STM_${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
        val partyRef = "party-e2e-${UUID.randomUUID()}"
        val customer = "E2E Customer ${UUID.randomUUID().toString().take(8)}"
        RenderStub.renderedHtml.clear()

        val created: Map<String, Any?> = Given {
            contentType("application/json")
            body(templateBody(code, "<h1>Statement for {{name}}</h1>"))
        } When {
            post("/api/v1/documents/templates")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val templateId = created["id"] as String
        assertThat(created["status"]).isEqualTo("DRAFT")
        assertThat(created["code"]).isEqualTo(code)

        val published: Map<String, Any?> = Given {
            contentType("application/json")
        } When {
            post("/api/v1/documents/templates/$templateId/publish")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(published["status"]).isEqualTo("PUBLISHED")

        val rendered = renderDocument(code, partyRef, customer)
        val documentId = rendered["id"] as String
        assertThat(rendered["templateCode"]).isEqualTo(code)
        assertThat(rendered["sha256"] as String).isNotBlank()
        assertThat(rendered["contentType"]).isEqualTo("application/pdf")
        assertThat((rendered["sizeBytes"] as Number).toLong()).isPositive()
        assertThat(rendered["partyRef"]).isEqualTo(partyRef)

        // The merged template actually reached the render boundary, substitution included.
        assertThat(RenderStub.renderedHtml.single()).contains("Statement for $customer")

        assertDocumentReadable(documentId, rendered["sha256"] as String, partyRef)
    }

    private fun renderDocument(code: String, partyRef: String, customer: String): Map<String, Any?> = Given {
        contentType("application/json")
        body(
            """
            {
              "templateCode": "$code",
              "data": {"name": "$customer"},
              "partyRef": "$partyRef"
            }
            """.trimIndent(),
        )
    } When {
        post("/api/v1/documents/render")
    } Then {
        statusCode(201)
    } Extract {
        jsonPath().getMap("")
    }

    private fun assertDocumentReadable(documentId: String, sha256: String, partyRef: String) {
        val metadata: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/documents/$documentId")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(metadata["sha256"]).isEqualTo(sha256)

        val content: io.restassured.response.Response = Given {
            this
        } When {
            get("/api/v1/documents/$documentId/content")
        }
        assertThat(content.statusCode).isEqualTo(200)
        assertThat(content.contentType).contains("application/octet-stream")
        assertThat(content.body.asByteArray()).isNotEmpty()

        val browse: io.restassured.response.Response = Given {
            queryParam("partyRef", partyRef)
        } When {
            get("/api/v1/documents")
        }
        assertThat(browse.statusCode).isEqualTo(200)
        val rows = browse.body.jsonPath().getList<Map<String, Any?>>("")
        assertThat(rows.map { it["id"] }).contains(documentId)
        assertThat(browse.header("X-Total-Count").toInt()).isGreaterThanOrEqualTo(1)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `only published templates render`() {
        val code = "E2E_DRF_${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
        val templateId = createTemplate(code)

        // DRAFT: the render boundary is never reached; the refusal is a coded 422.
        renderExpectingStatus(code, 422)

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/documents/templates/$templateId/publish")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/documents/templates/$templateId/retire")
        } Then {
            statusCode(200)
        }

        // RETIRED: still not renderable.
        renderExpectingStatus(code, 422)

        // And an unknown template code refuses the same way, not a 500.
        renderExpectingStatus("E2E_NOPE_${UUID.randomUUID().toString().take(8).uppercase()}", 422)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the party browse rejects bad paging and unknown ids`() {
        Given {
            queryParam("partyRef", " ")
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(400)
        }

        Given {
            queryParam("partyRef", "party-e2e-x")
            queryParam("page", -1)
        } When {
            get("/api/v1/documents")
        } Then {
            statusCode(400)
        }

        Given {
            this
        } When {
            get("/api/v1/documents/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }

        Given {
            this
        } When {
            get("/api/v1/documents/${UUID.randomUUID()}/content")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewers are refused on the document rail entirely`() {
        Given {
            queryParam("limit", 5)
        } When {
            get("/api/v1/documents/templates")
        } Then {
            statusCode(403)
        }

        Given {
            contentType("application/json")
            body(templateBody("E2E_VIEWER_X", "<p>no</p>"))
        } When {
            post("/api/v1/documents/templates")
        } Then {
            statusCode(403)
        }
    }

    private fun createTemplate(code: String): String {
        val created: Map<String, Any?> = Given {
            contentType("application/json")
            body(templateBody(code, "<h1>draft</h1>"))
        } When {
            post("/api/v1/documents/templates")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        return created["id"] as String
    }

    private fun renderExpectingStatus(code: String, status: Int) {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "templateCode": "$code",
                  "data": {}
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/documents/render")
        } Then {
            statusCode(status)
        }
    }

    private fun templateBody(code: String, bodyHtml: String) = """
        {
          "code": "$code",
          "version": "1.0",
          "name": "E2E Template $code",
          "bodyHtml": "${bodyHtml.replace("\"", "\\\"")}",
          "locale": "en",
          "classification": "restricted",
          "createdBy": "e2e"
        }
    """.trimIndent()
}
