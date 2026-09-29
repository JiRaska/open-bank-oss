// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerBusinessResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** The initiator/signer is the token's HUMAN on every route; a body naming someone else never reaches kyb-service. */
class CustomerBusinessResourceTest {

    private val caller = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val kyb = "http://kyb-service.kyb.svc:8157"
    private val docs = "http://document-service.documents.svc:8143"

    /** One past the edge's own URL bound. kyb-service imposes no maximum name length at all. */
    private val maxTermPlusOne = CustomerBusinessResource.MAX_TERM + 1

    private fun resource(upstream: UpstreamClient): CustomerBusinessResource {
        val merge = mockk<PartyMergeResolver> { every { resolve(any()) } answers { firstArg() } }
        return CustomerBusinessResource(upstream, merge).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { subject } returns caller.toString()
            }
            objectMapper = ObjectMapper()
            kybServiceUrl = kyb
            documentServiceUrl = docs
        }
    }

    @Test
    fun `start fills in the initiator from the token and forwards the rest of the body`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        val party = slot<String>()
        every { upstream.post(capture(url), capture(party), capture(body), any()) } returns Response.status(201).build()

        val response = resource(upstream).start("""{"scheme":"CZ_ICO","identifier":"45274649"}""")

        assertThat(response.status).isEqualTo(201)
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/cases")
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(body.captured).contains("\"initiatorPartyId\":\"$caller\"")
        assertThat(body.captured).contains("\"identifier\":\"45274649\"")
    }

    @Test
    fun `start refuses a body naming another initiator before upstream`() {
        val upstream = mockk<UpstreamClient>()
        val response = resource(
            upstream,
        ).start("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"$stranger"}""")
        assertThat(response.status).isEqualTo(403)
        io.mockk.verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `claim binds the token party, never a body-supplied one`() {
        val upstream = mockk<UpstreamClient>()
        val baseUrl = slot<String>()
        val path = slot<String>()
        val body = slot<String>()
        every {
            upstream.postToService(capture(baseUrl), capture(path), any(), capture(body), any())
        } returns Response.ok().build()

        resource(upstream).claim("inv_9f3ab21c-4d0e")

        assertThat(baseUrl.captured).isEqualTo(kyb)
        assertThat(path.captured).isEqualTo("/api/v1/kyb/invitations/inv_9f3ab21c-4d0e/claim")
        assertThat(body.captured).isEqualTo("""{"partyId":"$caller"}""")
    }

    @Test
    fun `claim refuses a token that is not opaque and URL-safe, before upstream`() {
        // The structured upstream URI already prevents the path from changing the authority. The
        // token's own contract is narrower, so malformed input is still a 400 here at the edge.
        // libs-runtime maps IllegalArgumentException to 400 — never a service-local mapper (#526).
        val upstream = mockk<UpstreamClient>()

        listOf("tok/with space", "http://evil.example", "short", "a".repeat(129))
            .forEach { bad ->
                assertThatThrownBy { resource(upstream).claim(bad) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }

        io.mockk.verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
        io.mockk.verify(exactly = 0) { upstream.postToService(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `schemes refuses a country filter that is not an alpha-2 code`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        assertThatThrownBy { resource(upstream).schemes("../../etc") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // Blank still means "no filter", which is what an absent query parameter has always meant.
        resource(upstream).schemes("")
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/schemes")
        resource(upstream).schemes("CZ")
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/schemes?country=CZ")
    }

    @Test
    fun `mine is scoped to the token party on the upstream query`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()
        resource(upstream).mine()
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/cases?partyId=$caller")
    }

    @Test
    fun `search forwards the trimmed terms and the caller's own party, and encodes them`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val party = slot<String>()
        every { upstream.get(capture(url), capture(party)) } returns Response.ok().build()

        val response = resource(upstream).search("CZ", "  Příklad & syn  ", " Ústí nad Labem ", 10)

        assertThat(response.status).isEqualTo(200)
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(url.captured).startsWith("$kyb/api/v1/kyb/registry/search?")
        // Encoded, not interpolated: an ampersand in a company name would otherwise append a
        // parameter of the caller's choosing to the upstream query.
        assertThat(url.captured).contains("name=P%C5%99%C3%ADklad+%26+syn")
        // A diacritic AND spaces, deliberately: "Praha" encodes to itself, so asserting it would
        // pass against raw interpolation — and `city` is the one parameter with no shape regex.
        assertThat(url.captured).contains("city=%C3%9Ast%C3%AD+nad+Labem")
        assertThat(url.captured).contains("limit=10")
    }

    @Test
    fun `search drops a blank town rather than sending an empty filter`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok().build()

        resource(upstream).search("CZ", "Kofola", "   ", null)

        assertThat(url.captured).doesNotContain("city=")
        assertThat(url.captured).doesNotContain("limit=")
    }

    @Test
    fun `search rejects a malformed request WITHOUT an upstream round trip`() {
        val upstream = mockk<UpstreamClient>()

        // An absent parameter is a 400 from requireNotNull, never the 500 a non-null JAX-RS
        // parameter would give (root CLAUDE.md, gate `nonnull-jaxrs-param-ratchet`).
        listOf<() -> Unit>(
            { resource(upstream).search(null, "Kofola", null, null) },
            { resource(upstream).search("CZ", null, null, null) },
            { resource(upstream).search("CZE", "Kofola", null, null) },
            { resource(upstream).search("CZ", "ab", null, null) },
            { resource(upstream).search("CZ", "Kofola", null, 0) },
            { resource(upstream).search("CZ", "Kofola", null, 500) },
            { resource(upstream).search("CZ", "x".repeat(maxTermPlusOne), null, null) },
            { resource(upstream).search("CZ", "Kofola", "x".repeat(maxTermPlusOne), null) },
        ).forEach { call ->
            assertThatThrownBy { call() }.isInstanceOf(IllegalArgumentException::class.java)
        }

        io.mockk.verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a long company name is searchable — the cap is a URL bound, not a claim about the register`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok().build()

        // kyb-service requires only that the name is not blank, so a cap here is a bound this edge
        // imposes on its own URL. An earlier version set it at 100, which silently made long-named
        // entities unsearchable — Czech cooperative and association names run past that routinely.
        val long = "Zemědělské družstvo " + "Horní Dolní ".repeat(10)
        resource(upstream).search("CZ", long, null, null)

        assertThat(url.captured).contains("name=Zem%C4%9Bd%C4%9Blsk%C3%A9")
    }

    @Test
    fun `questionnaire and declarations are PUT to kyb as the token human with the body untouched`() {
        val upstream = mockk<UpstreamClient>()
        val urls = mutableListOf<String>()
        val parties = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        every { upstream.put(capture(urls), capture(parties), capture(bodies)) } returns Response.ok("{}").build()
        val case = UUID.randomUUID()
        val q = """{"purpose":"OPERATING_ACCOUNT","cashIntensive":false}"""
        val d = """{"uboConfirmed":true,"peps":[],"truthful":true}"""

        resource(upstream).questionnaire(case, q)
        resource(upstream).declarations(case, d)

        assertThat(urls).containsExactly(
            "$kyb/api/v1/kyb/cases/$case/questionnaire",
            "$kyb/api/v1/kyb/cases/$case/declarations",
        )
        assertThat(parties).containsOnly(caller.toString())
        assertThat(bodies).containsExactly(q, d)
    }

    @Test
    fun `agreement and accept are POSTed to kyb as the token human, lang pinned`() {
        val upstream = mockk<UpstreamClient>()
        val urls = mutableListOf<String>()
        val parties = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        every { upstream.post(capture(urls), capture(parties), capture(bodies), any()) } returns
            Response.ok("{}").build()
        val case = UUID.randomUUID()
        val accept = """{"disclosures":[{"code":"VOP_CS","version":"1.1.0","sha256":"ab"}]}"""

        resource(upstream).agreement(case, "EN")
        resource(upstream).agreement(case, null)
        resource(upstream).acceptAgreement(case, accept)

        assertThat(urls).containsExactly(
            "$kyb/api/v1/kyb/cases/$case/agreement?lang=en",
            "$kyb/api/v1/kyb/cases/$case/agreement",
            "$kyb/api/v1/kyb/cases/$case/agreement/accept",
        )
        assertThat(parties).containsOnly(caller.toString())
        assertThat(bodies.last()).isEqualTo(accept)

        assertThatThrownBy { resource(upstream).agreement(case, "cs&x=1") }
            .isInstanceOf(IllegalArgumentException::class.java)
        io.mockk.verify(exactly = 3) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `questionnaire prefill is read from kyb as the token human and passed through`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val party = slot<String>()
        val case = UUID.randomUUID()
        val prefill =
            """{"knownPersons":[{"name":"Jana","partyId":"$caller","pep":null}],""" +
                """"previousQuestionnaire":null}"""
        every { upstream.get(capture(url), capture(party)) } returns Response.ok(prefill).build()

        val ok = resource(upstream).questionnairePrefill(case)

        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/cases/$case/questionnaire/prefill")
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(ok.entity).isEqualTo(prefill)

        every { upstream.get(any(), any()) } returns Response.status(404).entity("""{"error":"NOT_INVOLVED"}""").build()
        assertThat(resource(upstream).questionnairePrefill(case).status).isEqualTo(404)
    }

    private fun caseDocUpstream(case: UUID, doc: UUID, kybStatus: Int, docCaseRef: UUID): UpstreamClient =
        mockk<UpstreamClient>().also { u ->
            every { u.get("$kyb/api/v1/kyb/cases/$case", caller.toString()) } returns
                Response.status(kybStatus).entity("""{"id":"$case"}""").build()
            every { u.get("$docs/api/v1/documents/$doc", caller.toString()) } returns
                Response.ok("""{"id":"$doc","partyRef":"${UUID.randomUUID()}","caseRef":"$docCaseRef"}""").build()
            every { u.getRaw("$docs/api/v1/documents/$doc/content", caller.toString(), any()) } returns
                Response.ok(byteArrayOf(1, 2)).type("application/pdf").build()
        }

    @Test
    fun `a case participant streams a document of that case without any mandate`() {
        val case = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = caseDocUpstream(case, doc, 200, case)

        val resp = resource(upstream).caseDocumentContent(case, doc)

        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.mediaType.toString()).isEqualTo("application/pdf")
    }

    @Test
    fun `a non-participant gets kyb's refusal and no document is ever read`() {
        listOf(403, 404).forEach { code ->
            val case = UUID.randomUUID()
            val doc = UUID.randomUUID()
            val upstream = caseDocUpstream(case, doc, code, case)

            assertThat(resource(upstream).caseDocumentContent(case, doc).status).isEqualTo(code)
            io.mockk.verify(exactly = 0) { upstream.get(match { it.startsWith(docs) }, any()) }
            io.mockk.verify(exactly = 0) { upstream.getRaw(any(), any(), any()) }
        }
    }

    @Test
    fun `a document of ANOTHER case is 404 and its content is never fetched`() {
        val case = UUID.randomUUID()
        val doc = UUID.randomUUID()
        val upstream = caseDocUpstream(case, doc, 200, UUID.randomUUID())

        assertThat(resource(upstream).caseDocumentContent(case, doc).status).isEqualTo(404)
        io.mockk.verify(exactly = 0) { upstream.getRaw(any(), any(), any()) }
    }

    @Test
    fun `kyb refusals on the new routes reach the client unchanged`() {
        val upstream = mockk<UpstreamClient>()
        val case = UUID.randomUUID()
        every { upstream.put(match { it.endsWith("/questionnaire") }, any(), any()) } returns
            Response.status(400).entity("""{"error":"purpose required"}""").build()
        every { upstream.put(match { it.endsWith("/declarations") }, any(), any()) } returns
            Response.status(422).entity("""{"error":"truthful"}""").build()
        every { upstream.post(match { it.endsWith("/agreement/accept") }, any(), any(), any()) } returns
            Response.status(409).entity("""{"error":"DISCLOSURES_STALE"}""").build()
        every { upstream.post(match { it.contains("/agreement?") }, any(), any(), any()) } returns
            Response.status(409).entity("""{"error":"QUESTIONNAIRE_MISSING"}""").build()

        val r = resource(upstream)
        assertThat(r.questionnaire(case, "{}").status).isEqualTo(400)
        assertThat(r.declarations(case, "{}").status).isEqualTo(422)
        val stale = r.acceptAgreement(case, "{}")
        assertThat(stale.status).isEqualTo(409)
        assertThat(stale.entity as String).contains("DISCLOSURES_STALE")
        assertThat(r.agreement(case, "cs").status).isEqualTo(409)
    }
}
