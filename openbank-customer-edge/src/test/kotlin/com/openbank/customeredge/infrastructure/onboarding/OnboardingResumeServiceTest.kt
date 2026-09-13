// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.KeycloakAdminClient
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Named so detekt's TooGenericExceptionThrown does not fire at the throw site. */
private class UpstreamDown : RuntimeException("connection refused")

class OnboardingResumeServiceTest {

    private lateinit var upstream: UpstreamClient
    private lateinit var keycloak: KeycloakAdminClient
    private lateinit var audit: EdgeAuditPublisher
    private lateinit var store: PendingOnboardingStore
    private val mapper = ObjectMapper()
    private lateinit var svc: OnboardingResumeService

    private val caseId = "11111111-1111-1111-1111-111111111111"
    private val caller = "22222222-2222-2222-2222-222222222222"
    private val existing = "33333333-3333-3333-3333-333333333333"

    private fun pending() = PendingOnboarding(
        caseId = caseId,
        callerPartyId = caller,
        legalName = "Jan Novak",
        email = "jan@example.cz",
        dateOfBirth = "1976-05-06",
        nationality = "CZ",
        phone = null,
    )

    private fun service(enabled: Boolean) = OnboardingResumeService(
        upstream,
        keycloak,
        audit,
        store,
        mapper,
        partyServiceUrl = "http://party",
        pidServiceUrl = "http://pid",
        resumeEnabled = enabled,
    )

    @BeforeEach
    fun setUp() {
        upstream = mockk(relaxed = true)
        keycloak = mockk(relaxed = true)
        audit = mockk(relaxed = true)
        store = mockk(relaxed = true)
        every { upstream.post(any(), any(), any(), any()) } returns Response.ok("{}").build()
        every { keycloak.setPartyIdAttribute(any(), any()) } returns true
        every { store.find(caseId) } returns pending()
        svc = service(enabled = true)
    }

    private fun decidedEvent(verdict: String, linkPartyId: String?): String {
        val payload = mapper.createObjectNode()
        payload.put("verdict", verdict)
        linkPartyId?.let { payload.put("linkPartyId", it) }
        val env = mapper.createObjectNode()
        env.put("eventType", "IdentityVerificationCaseDecided")
        env.put("aggregateId", caseId)
        env.replace("payload", payload)
        return mapper.writeValueAsString(env)
    }

    @Test
    fun `LINK_TO_EXISTING links the sub to the decided party and clears the pending record`() {
        svc.onPartyEvent(decidedEvent("LINK_TO_EXISTING", existing))

        val url = slot<String>()
        verify { upstream.post(capture(url), existing, match { it.contains("KEYCLOAK_ID") }, "relink-$caller") }
        assertThat(url.captured).isEqualTo("http://pid/api/v1/parties/$existing/external-ids")
        verify { keycloak.setPartyIdAttribute(caller, existing) }
        verify { store.delete(caseId) }
    }

    @Test
    fun `DISTINCT_NEW creates the party and registers the no-RC identity`() {
        svc.onPartyEvent(decidedEvent("DISTINCT_NEW", null))

        verify { upstream.post("http://party/api/v1/parties", caller, any(), "onboarding-resume-$caseId") }
        verify {
            upstream.post(
                "http://pid/api/v1/parties/register-identity",
                caller,
                match {
                    !it.contains("birthNumberRaw")
                },
                any(),
            )
        }
        verify { store.delete(caseId) }
    }

    @Test
    fun `REJECT onboards nobody but clears the pending record`() {
        svc.onPartyEvent(decidedEvent("REJECT", null))

        verify(exactly = 0) {
            upstream.post(match { it.contains("/parties") && !it.contains("cases") }, any(), any(), any())
        }
        verify { store.delete(caseId) }
    }

    @Test
    fun `a non-decision event is ignored`() {
        svc.onPartyEvent("""{"eventType":"PartyCreated","aggregateId":"$caseId"}""")
        verify(exactly = 0) { store.find(any()) }
    }

    @Test
    fun `with the flag off nothing happens`() {
        service(enabled = false).onPartyEvent(decidedEvent("LINK_TO_EXISTING", existing))
        verify(exactly = 0) { store.find(any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown caseId (no pending record) is a no-op`() {
        every { store.find(caseId) } returns null
        svc.onPartyEvent(decidedEvent("DISTINCT_NEW", null))
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    /**
     * The #5698 pair. Before this fix the handler caught every downstream failure, logged it, and
     * returned normally — acking the Kafka message — while a `finally` deleted the pending record
     * regardless. So a few seconds of party-service downtime stranded the applicant permanently:
     * the event was gone AND the record a replay would have needed was gone with it.
     *
     * Both halves are asserted, because a test that cannot tell a transient downstream failure from
     * a malformed payload proves nothing about either.
     */
    @Test
    fun `a persistent upstream failure is RETHROWN and the pending record is KEPT for a replay`() {
        every { upstream.post("http://party/api/v1/parties", any(), any(), any()) } throws UpstreamDown()

        assertThrows<UpstreamDown> { svc.onPartyEvent(decidedEvent("DISTINCT_NEW", null)) }

        verify(exactly = 3) { upstream.post("http://party/api/v1/parties", any(), any(), any()) }
        verify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `a transient upstream failure is retried, succeeds, and then clears the pending record`() {
        var calls = 0
        every { upstream.post("http://party/api/v1/parties", any(), any(), any()) } answers {
            calls++
            if (calls == 1) throw UpstreamDown() else Response.ok("{}").build()
        }

        svc.onPartyEvent(decidedEvent("DISTINCT_NEW", null))

        verify(exactly = 2) { upstream.post("http://party/api/v1/parties", any(), any(), any()) }
        verify(exactly = 1) { store.delete(caseId) }
    }

    @Test
    fun `a malformed payload is acked, touching neither upstream nor the pending store`() {
        svc.onPartyEvent("not json")
        svc.onPartyEvent("""{"eventType":"IdentityVerificationCaseDecided"}""") // no aggregateId

        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
        verify(exactly = 0) { store.delete(any()) }
    }

    @Test
    fun `an unknown verdict is acked and clears the pending record`() {
        svc.onPartyEvent(decidedEvent("SOMETHING_ELSE", null))

        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
        verify(exactly = 1) { store.delete(caseId) }
    }
}
