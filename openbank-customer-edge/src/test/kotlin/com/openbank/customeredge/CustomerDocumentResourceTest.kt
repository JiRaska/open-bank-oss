// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.CustomerDocumentResource
import com.openbank.customeredge.infrastructure.rest.DelegationGrants
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Ownership is the whole point of these edge routes (ADR-0169 D1): a customer may only ever reach
 * their OWN documents/ceremonies, and `partyRef` on writes comes from the token, never the body.
 */
class CustomerDocumentResourceTest {

    private val caller: UUID = UUID.randomUUID()
    private val docSvc = "http://document-service.documents.svc:8143"

    private fun resource(upstream: UpstreamClient): CustomerDocumentResource = CustomerDocumentResource(
        upstream,
        // These tests are about ownership, not delegation; a checker pointed at an
        // unreachable service denies every share, which is the pre-existing behaviour
        // each case here asserts.
        DelegationGrants(upstream).apply { delegationServiceUrl = "http://delegation.invalid" },
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns caller.toString()
            every { subject } returns caller.toString()
        }
        documentServiceUrl = docSvc
    }

    @Test
    fun `ensureAgreement forces partyRef to the token and passes the chosen language`() {
        val upstream = mockk<UpstreamClient>()
        val bodySlot = slot<String>()
        every { upstream.post(any(), any(), capture(bodySlot), any()) } returns Response.ok("{}").build()

        // A malicious client tries to smuggle a different partyRef — it must be ignored.
        resource(upstream).ensureAgreement("""{"partyRef":"${UUID.randomUUID()}","lang":"en"}""")

        assertThat(bodySlot.captured).contains("\"partyRef\":\"$caller\"")
        assertThat(bodySlot.captured).contains("\"lang\":\"en\"")
    }

    @Test
    fun `listDocuments queries upstream with the token party, never a client-supplied one`() {
        val upstream = mockk<UpstreamClient>()
        // Capture ALL urls, not the last one: listDocuments also asks delegation-service which
        // documents were shared with the caller, so "the last call" is no longer the documents
        // read. The property under test is unchanged — the party in the documents query is the
        // token's, never one the client supplied.
        val urls = mutableListOf<String>()
        every { upstream.get(capture(urls), any()) } returns Response.ok("[]").build()

        resource(upstream).listDocuments()

        assertThat(urls).contains("$docSvc/api/v1/documents?partyRef=$caller")
        assertThat(urls.filter { it.startsWith(docSvc) }).allSatisfy {
            assertThat(it).contains(caller.toString())
        }
    }

    @Test
    fun `listDocuments strips storage coordinates and drops foreign rows`() {
        val upstream = mockk<UpstreamClient>()
        val otherParty = UUID.randomUUID()
        every { upstream.get(any(), any()) } returns Response.ok(
            """
            [
              {"id":"$DOC_ID","partyRef":"$caller","templateCode":"RAMCOVA_SMLOUVA","templateVersion":"1",
               "contentType":"application/pdf","sizeBytes":1024,"status":"SIGNED","createdAt":"2026-07-20T10:00:00Z",
               "storageKey":"s3://bucket/secret-key","sha256":"deadbeef"},
              {"id":"${UUID.randomUUID()}","partyRef":"$otherParty","templateCode":"VOP","status":"GENERATED"}
            ]
            """.trimIndent(),
        ).build()

        val body = resource(upstream).listDocuments().entity as String

        assertThat(body).contains("RAMCOVA_SMLOUVA")
        assertThat(body).doesNotContain("storageKey")
        assertThat(body).doesNotContain("sha256")
        assertThat(body).doesNotContain(otherParty.toString())
    }

    @Test
    fun `listDocuments hides superseded (ARCHIVED) revisions`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            """
            [
              {"id":"$DOC_ID","partyRef":"$caller","templateCode":"RAMCOVA_SMLOUVA_CS","status":"SIGNED",
               "createdAt":"2026-07-20T10:00:00Z"},
              {"id":"${UUID.randomUUID()}","partyRef":"$caller","templateCode":"RAMCOVA_SMLOUVA_EN",
               "status":"ARCHIVED","createdAt":"2026-07-19T10:00:00Z"}
            ]
            """.trimIndent(),
        ).build()

        val body = resource(upstream).listDocuments().entity as String

        assertThat(body).contains("RAMCOVA_SMLOUVA_CS")
        assertThat(body).doesNotContain("RAMCOVA_SMLOUVA_EN")
    }

    @Test
    fun `listDocuments keeps an ARCHIVED document that nothing replaced`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns Response.ok(
            """
            [
              {"id":"$DOC_ID","partyRef":"$caller","templateCode":"UCET_SMLOUVA_CS","status":"ARCHIVED",
               "createdAt":"2026-07-17T17:13:45Z"}
            ]
            """.trimIndent(),
        ).build()

        val body = resource(upstream).listDocuments().entity as String

        // Every account agreement in the sandbox sits in ARCHIVED. Hiding it would remove the
        // customer's only copy of a contract they are party to.
        assertThat(body).contains("UCET_SMLOUVA_CS")
    }

    @Test
    fun `documentContent returns 404 for a document owned by another party (no existence leak)`() {
        val upstream = mockk<UpstreamClient>()
        val otherParty = UUID.randomUUID()
        every { upstream.get(match { it.endsWith("/documents/$DOC_ID") }, any()) } returns
            Response.ok("""{"id":"$DOC_ID","partyRef":"$otherParty"}""").build()

        val resp = resource(upstream).documentContent(DOC_ID)

        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `documentContent streams the PDF when the caller owns the document`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/documents/$DOC_ID") }, any()) } returns
            Response.ok("""{"id":"$DOC_ID","partyRef":"$caller"}""").build()
        every { upstream.getRaw(match { it.endsWith("/documents/$DOC_ID/content") }, any(), any()) } returns
            Response.ok(byteArrayOf(1, 2, 3)).type("application/pdf").build()

        val resp = resource(upstream).documentContent(DOC_ID)

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.mediaType.toString()).isEqualTo("application/pdf")
    }

    @Test
    fun `ceremony returns 404 when the caller is not one of its signers`() {
        val upstream = mockk<UpstreamClient>()
        val someoneElse = UUID.randomUUID()
        every { upstream.get(match { it.contains("/signature-ceremonies/$CEREMONY_ID") }, any()) } returns
            Response.ok("""{"id":"$CEREMONY_ID","signers":[{"partyRef":"$someoneElse"}]}""").build()

        val resp = resource(upstream).ceremony(CEREMONY_ID)

        assertThat(resp.status).isEqualTo(404)
    }

    @Test
    fun `recordDecision forces partyRef to the token and forwards the SCA evidenceRef`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/signature-ceremonies/$CEREMONY_ID") }, any()) } returns
            Response.ok("""{"id":"$CEREMONY_ID","signers":[{"partyRef":"$caller"}]}""").build()
        val bodySlot = slot<String>()
        every {
            upstream.post(match { it.endsWith("/decisions") }, any(), capture(bodySlot), any())
        } returns Response.ok("{}").build()

        val challengeId = UUID.randomUUID().toString()
        val resp = resource(upstream).recordDecision(
            CEREMONY_ID,
            """{"partyRef":"${UUID.randomUUID()}","decision":"SIGNED","evidenceRef":"$challengeId"}""",
        )

        assertThat(resp.status).isEqualTo(200)
        assertThat(bodySlot.captured).contains("\"partyRef\":\"$caller\"")
        assertThat(bodySlot.captured).contains("\"decision\":\"SIGNED\"")
        assertThat(bodySlot.captured).contains("\"evidenceRef\":\"$challengeId\"")
    }

    @Test
    fun `recordDecision rejects a body with no decision`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/signature-ceremonies/$CEREMONY_ID") }, any()) } returns
            Response.ok("""{"id":"$CEREMONY_ID","signers":[{"partyRef":"$caller"}]}""").build()

        val resp = resource(upstream).recordDecision(CEREMONY_ID, """{"evidenceRef":"x"}""")

        assertThat(resp.status).isEqualTo(400)
    }

    private companion object {
        val DOC_ID: UUID = UUID.randomUUID()
        val CEREMONY_ID: UUID = UUID.randomUUID()
    }
}
