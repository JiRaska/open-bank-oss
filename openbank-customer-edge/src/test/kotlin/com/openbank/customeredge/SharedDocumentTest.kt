// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerDocumentResource
import com.openbank.customeredge.infrastructure.rest.DelegationGrants
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A shared DOCUMENT, read by the person it was shared with (ADR-0232, issue #3615).
 *
 * Delegated access over a document was a grant nobody could use: the app could offer one and this
 * resource still answered 404, because ownership was the only question asked. The share button on
 * a document row made that visible — it produced a grant that led nowhere.
 */
class SharedDocumentTest {

    private fun resourceFor(upstream: UpstreamClient, caller: UUID): CustomerDocumentResource {
        val grants = DelegationGrants(upstream).apply { delegationServiceUrl = "http://delegation" }
        return CustomerDocumentResource(upstream, grants).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { subject } returns caller.toString()
            }
            documentServiceUrl = "http://documents"
        }
    }

    private fun docMeta(id: UUID, owner: UUID) = Response.ok(
        """{"id":"$id","partyRef":"$owner","templateCode":"RAMCOVA_SMLOUVA","status":"ACTIVE","createdAt":"2026-08-01"}""",
    ).build()

    private fun granted(yes: Boolean) = Response.ok("""{"granted":$yes}""").build()

    @Test
    fun `the person a document was shared with can open it`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/documents/$doc") }, any()) } returns docMeta(doc, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(true)
        every { upstream.getRaw(match { it.contains("/content") }, any(), any()) } returns
            Response.ok("%PDF-1.4").build()

        assertThat(resourceFor(upstream, grantee).documentContent(doc).status).isEqualTo(200)
    }

    @Test
    fun `a stranger gets 404, not 403 — a document id must not be confirmed to exist`() {
        val stranger = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/documents/$doc") }, any()) } returns docMeta(doc, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(false)

        val resp = resourceFor(upstream, stranger).documentContent(doc)

        assertThat(resp.status).isEqualTo(404)
        verify(exactly = 0) { upstream.getRaw(match { it.contains("/content") }, any(), any()) }
    }

    @Test
    fun `an owner is never asked about delegation`() {
        val owner = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/documents/$doc") }, any()) } returns docMeta(doc, owner)
        every { upstream.getRaw(any(), any(), any()) } returns Response.ok("%PDF-1.4").build()

        assertThat(resourceFor(upstream, owner).documentContent(doc).status).isEqualTo(200)
        verify(exactly = 0) { upstream.post(match { it.contains("/delegations/check") }, any(), any()) }
    }

    @Test
    fun `a delegation-service outage denies rather than guessing`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/documents/$doc") }, any()) } returns docMeta(doc, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } throws
            RuntimeException("connection refused")

        assertThat(resourceFor(upstream, grantee).documentContent(doc).status).isEqualTo(404)
    }

    @Test
    fun `a shared document appears in the list, marked, and after the caller's own`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("documents?partyRef=$grantee") }, any()) } returns Response.ok(
            """[{"id":"$mine","partyRef":"$grantee","templateCode":"VOP","status":"ACTIVE","createdAt":"2026-08-02"}]""",
        ).build()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns Response.ok(
            """[{"status":"ACTIVE","resourceType":"DOCUMENT","resourceId":"$theirs","capabilities":["OBJECT_READ"]}]""",
        ).build()
        every { upstream.get(match { it.endsWith("/documents/$theirs") }, any()) } returns docMeta(theirs, owner)

        val body = ObjectMapper().readTree(resourceFor(upstream, grantee).listDocuments().entity.toString())

        assertThat(body).hasSize(2)
        assertThat(body[0].path("sharedWithMe").asBoolean(false)).isFalse()
        assertThat(body[1].path("id").asText()).isEqualTo(theirs.toString())
        assertThat(body[1].path("sharedWithMe").asBoolean(false)).isTrue()
    }

    @Test
    fun `the caller's own documents survive a delegation-service outage`() {
        val grantee = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("documents?partyRef=$grantee") }, any()) } returns Response.ok(
            """[{"id":"$mine","partyRef":"$grantee","templateCode":"VOP","status":"ACTIVE","createdAt":"2026-08-02"}]""",
        ).build()
        every { upstream.get(match { it.contains("/delegations/grantee/") }, any()) } throws
            RuntimeException("connection refused")

        val body = ObjectMapper().readTree(resourceFor(upstream, grantee).listDocuments().entity.toString())

        assertThat(body).hasSize(1)
        assertThat(body[0].path("id").asText()).isEqualTo(mine.toString())
    }

    @Test
    fun `an offered but unaccepted share does not put the document in the list`() {
        val grantee = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("documents?partyRef=$grantee") }, any()) } returns
            Response.ok("[]").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns Response.ok(
            """[{"status":"OFFERED","resourceType":"DOCUMENT","resourceId":"$theirs","capabilities":["OBJECT_READ"]}]""",
        ).build()

        val body = ObjectMapper().readTree(resourceFor(upstream, grantee).listDocuments().entity.toString())

        assertThat(body).isEmpty()
    }
}
