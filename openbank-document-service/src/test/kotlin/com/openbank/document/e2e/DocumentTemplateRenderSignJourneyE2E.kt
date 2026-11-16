// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.e2e

import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * End-to-end journey for a document's whole legal life: **create a template as a draft, preview
 * its rendering, publish it, render a real PDF document from it through the renderer sidecar,
 * open a two-signer ceremony over the document, watch the signer order and the single-use SCA
 * evidence enforce themselves, complete the ceremony so the document is sealed SIGNED — and read
 * every state back through its own GET.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres and Valkey (`PostgresRedisTestResource`, per-class Testcontainers) with
 * Kafka's outgoing channel in memory. No use case or repository is called directly; the two
 * sidecars the lifecycle travels to (the WeasyPrint renderer and sca-service's challenge consume)
 * are served by [SidecarStubResource].
 *
 * ### Service boundaries — what is NOT claimed here
 *
 * The renderer's pixels and the SCA device's approval are stubbed, not exercised: this journey
 * proves document-service *calls the right routes and honours what comes back* — the single-use
 * 409 on a replayed challenge is asserted against the stub, not against a real sca-service. The
 * client signature and PAdES seal run on the service's DEV-ONLY ephemeral identity, which is the
 * documented %test posture (`openbank.signature.allow-ephemeral-seals: true`).
 *
 * ### Why the read-backs matter
 *
 * The write endpoints return the state they just computed, which is the same code path that
 * computed it. Reading the template, document and ceremony back with separate `GET`s after each
 * mutation is what proves the change was persisted; the ceremony's completion is only real when
 * the *document* reads back SIGNED, because the seal must land before the terminal status does.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(SidecarStubResource::class)
@QuarkusTestResource(DocumentTemplateRenderSignJourneyE2E.InMemoryKafkaResource::class)
@TestProfile(DocumentTemplateRenderSignJourneyE2E.JourneyProfile::class)
class DocumentTemplateRenderSignJourneyE2E {

    /** The document-generated event rides the in-memory connector — no broker in this JVM. */
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /** RBAC is asserted through `@RolesAllowed`; the advisory OPA PEP has no sidecar in tests. */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `1 - an operator creates a template draft and it reads back`() {
        val created = given()
            .contentType("application/json")
            .body(createTemplateBody())
            .post("/api/v1/documents/templates")
        created.then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("code", equalTo(TEMPLATE_CODE))
            .body("version", equalTo("1"))
            .body("status", equalTo("DRAFT"))
        templateId = created.jsonPath().getUUID("id")

        given().get("/api/v1/documents/templates/$templateId").then()
            .statusCode(200)
            .body("code", equalTo(TEMPLATE_CODE))
            .body("bodyHtml", equalTo(TEMPLATE_BODY))

        given().get("/api/v1/documents/templates").then()
            .statusCode(200)
            .body("code", hasItem(TEMPLATE_CODE))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `2 - the preview endpoint merges data into the template html`() {
        given()
            .contentType("application/json")
            .body(
                """{"bodyHtml":"<h1>Agreement for {{party.name}}</h1>","data":{"party":{"name":"$HOLDER_NAME"}}}""",
            )
            .post("/api/v1/documents/templates/preview")
            .then()
            .statusCode(200)
            .body("renderedHtml", equalTo("<h1>Agreement for $HOLDER_NAME</h1>"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `3 - publishing the template makes it renderable`() {
        given().contentType("application/json").post("/api/v1/documents/templates/$templateId/publish").then()
            .statusCode(200)
            .body("status", equalTo("PUBLISHED"))

        given().get("/api/v1/documents/templates/$templateId").then()
            .statusCode(200)
            .body("status", equalTo("PUBLISHED"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `4 - rendering a document stores real bytes and reads back through every view`() {
        val rendered = given()
            .contentType("application/json")
            .body(
                """
                {
                  "templateCode": "$TEMPLATE_CODE",
                  "data": {"party": {"name": "$HOLDER_NAME"}},
                  "partyRef": "$PARTY_A",
                  "caseRef": "E2E-CASE-1",
                  "contentType": "application/pdf"
                }
                """.trimIndent(),
            )
            .post("/api/v1/documents/render")
        rendered.then()
            .statusCode(201)
            .body("templateCode", equalTo(TEMPLATE_CODE))
            .body("templateVersion", equalTo("1"))
            .body("status", equalTo("GENERATED"))
            .body("contentType", equalTo("application/pdf"))
            .body("sizeBytes", notNullValue())
            .body("sha256", notNullValue())
        documentId = rendered.jsonPath().getUUID("id")

        // Metadata read-back.
        given().get("/api/v1/documents/$documentId").then()
            .statusCode(200)
            .body("id", equalTo(documentId.toString()))
            .body("status", equalTo("GENERATED"))
            .body("partyRef", equalTo(PARTY_A.toString()))

        // Content read-back: the actual bytes round-tripped through the renderer sidecar and the
        // object store, and they hash to the recorded digest.
        val content = given().get("/api/v1/documents/$documentId/content")
        content.then()
            .statusCode(200)
            .contentType(equalTo("application/octet-stream"))
        val bytes = content.body.asByteArray()
        assertThat(bytes.size.toLong()).isEqualTo(rendered.jsonPath().getLong("sizeBytes"))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha256 = digest.joinToString("") { "%02x".format(it) }
        assertThat(sha256).isEqualTo(rendered.jsonPath().getString("sha256"))

        // Ownership projection and the party browse both see the document.
        given().get("/api/v1/documents/$documentId/owner").then()
            .statusCode(200)
            .body("id", equalTo(documentId.toString()))
            .body("partyId", equalTo(PARTY_A.toString()))
        given()
            .queryParam("partyRef", PARTY_A.toString())
            .get("/api/v1/documents")
            .then()
            .statusCode(200)
            .body("id", hasItem(documentId.toString()))
            .header("X-Total-Count", equalTo("1"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `5 - an operator opens a two-signer ceremony over the document`() {
        val opened = given()
            .contentType("application/json")
            .body(
                """{"documentId":"$documentId","signerPartyRefs":["$PARTY_A","$PARTY_B"],"signatureLevel":"ADVANCED"}""",
            )
            .post("/api/v1/signature-ceremonies")
        opened.then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("documentId", equalTo(documentId.toString()))
            .body("status", equalTo("PENDING"))
            .body("signatureLevel", equalTo("ADVANCED"))
            .body("signers.partyRef", hasItem(PARTY_A.toString()))
            .body("signers.partyRef", hasItem(PARTY_B.toString()))
        ceremonyId = opened.jsonPath().getUUID("id")

        // The document enters signing the moment its ceremony opens.
        given().get("/api/v1/documents/$documentId").then()
            .statusCode(200)
            .body("status", equalTo("PENDING_SIGNATURE"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `6 - signers must decide in order`() {
        given()
            .contentType("application/json")
            .body("""{"partyRef":"$PARTY_B","decision":"SIGNED","evidenceRef":"${UUID.randomUUID()}"}""")
            .post("/api/v1/signature-ceremonies/$ceremonyId/decisions")
            .then()
            .statusCode(400)

        // Nothing was recorded for the out-of-order attempt.
        given().get("/api/v1/signature-ceremonies/$ceremonyId").then()
            .statusCode(200)
            .body(
                "signers.find { it.partyRef == '$PARTY_B' }.status",
                equalTo("PENDING"),
            )
    }

    @Test
    @Order(7)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `7 - the first signer signs with single-use SCA evidence`() {
        val challengeId = UUID.randomUUID()
        given()
            .contentType("application/json")
            .body("""{"partyRef":"$PARTY_A","decision":"SIGNED","evidenceRef":"$challengeId"}""")
            .post("/api/v1/signature-ceremonies/$ceremonyId/decisions")
            .then()
            .statusCode(200)
            .body("status", equalTo("PARTIALLY_SIGNED"))
            .body("signers.find { it.partyRef == '$PARTY_A' }.status", equalTo("SIGNED"))

        // At-least-once replay: re-POSTing the same signer's decision converges instead of
        // re-spending the (now single-use) challenge — the stub would 409 the replay.
        given()
            .contentType("application/json")
            .body("""{"partyRef":"$PARTY_A","decision":"SIGNED","evidenceRef":"$challengeId"}""")
            .post("/api/v1/signature-ceremonies/$ceremonyId/decisions")
            .then()
            .statusCode(200)
            .body("status", equalTo("PARTIALLY_SIGNED"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `8 - the last signature completes the ceremony and seals the document SIGNED`() {
        given()
            .contentType("application/json")
            .body("""{"partyRef":"$PARTY_B","decision":"SIGNED","evidenceRef":"${UUID.randomUUID()}"}""")
            .post("/api/v1/signature-ceremonies/$ceremonyId/decisions")
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"))

        // Read-backs on BOTH aggregates: the ceremony is terminal and the seal has landed on the
        // document — a completed ceremony whose document never reached SIGNED is the defect this
        // guards (the terminal transition once went missing entirely).
        given().get("/api/v1/signature-ceremonies/$ceremonyId").then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"))
            .body("signers.find { it.partyRef == '$PARTY_B' }.status", equalTo("SIGNED"))
        given().get("/api/v1/documents/$documentId").then()
            .statusCode(200)
            .body("status", equalTo("SIGNED"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `9 - retire closes the template and the surface fails closed on the unknown`() {
        given().contentType("application/json").post("/api/v1/documents/templates/$templateId/retire").then()
            .statusCode(200)
            .body("status", equalTo("RETIRED"))
        given().get("/api/v1/documents/templates/$templateId").then()
            .statusCode(200)
            .body("status", equalTo("RETIRED"))

        // Unknown ids are 404, not empty envelopes.
        given().get("/api/v1/documents/templates/${UUID.randomUUID()}").then()
            .statusCode(404)
        given().get("/api/v1/signature-ceremonies/${UUID.randomUUID()}").then()
            .statusCode(404)
        given().get("/api/v1/documents/${UUID.randomUUID()}").then()
            .statusCode(404)

        // A null signer ref is a wire-level client error, refused before any ceremony state.
        given()
            .contentType("application/json")
            .body("""{"documentId":"$documentId","signerPartyRefs":[null]}""")
            .post("/api/v1/signature-ceremonies")
            .then()
            .statusCode(400)
    }

    @Test
    @Order(10)
    @TestSecurity(user = VIEWER, roles = ["ROLE_VIEWER"])
    fun `10 - a viewer can never create templates or open ceremonies`() {
        // ROLE_VIEWER is not in any document write role — the writes are refused before any state.
        given()
            .contentType("application/json")
            .body(createTemplateBody())
            .post("/api/v1/documents/templates")
            .then()
            .statusCode(403)
        given()
            .contentType("application/json")
            .body("""{"documentId":"$documentId","signerPartyRefs":["$PARTY_A"]}""")
            .post("/api/v1/signature-ceremonies")
            .then()
            .statusCode(403)
    }

    private fun createTemplateBody(): String =
        """
        {
          "code": "$TEMPLATE_CODE",
          "version": "1",
          "name": "E2E Agreement",
          "bodyHtml": "$TEMPLATE_BODY",
          "locale": "en",
          "classification": "restricted",
          "createdBy": "$OPERATOR"
        }
        """.trimIndent()

    companion object {
        const val OPERATOR = "e2e-document-operator"
        const val VIEWER = "e2e-document-viewer"
        const val TEMPLATE_CODE = "E2E_AGREEMENT"
        const val TEMPLATE_BODY = "<h1>Agreement for {{party.name}}</h1><p>Body</p>"
        const val HOLDER_NAME = "E2E Document Holder"
        val PARTY_A: UUID = UUID.fromString("11111111-2222-4333-8444-5555555555a1")
        val PARTY_B: UUID = UUID.fromString("11111111-2222-4333-8444-5555555555a2")

        /** State carried between the ordered tests — each id is minted by the surface itself. */
        lateinit var templateId: UUID
        lateinit var documentId: UUID
        lateinit var ceremonyId: UUID
    }
}
