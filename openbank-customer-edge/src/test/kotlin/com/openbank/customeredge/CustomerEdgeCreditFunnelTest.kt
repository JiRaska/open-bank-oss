// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.credit.CreditFunnelPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * ADR-0269 rule 8's telemetry.
 *
 * The interesting properties are all refusals and silences: a junk value must not reach the stream,
 * a client must not learn what the bank tracks, and the vocabulary must not be able to express
 * conversion.
 */
class CustomerEdgeCreditFunnelTest {

    private val party = UUID.randomUUID()
    private val funnel = mockk<CreditFunnelPublisher>(relaxed = true)

    private fun resource(): CustomerEdgeResource = CustomerEdgeResource(
        mockk<UpstreamClient>(relaxed = true),
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns null
            every { subject } returns party.toString()
        }
        objectMapper = ObjectMapper()
        creditFunnel = funnel
    }

    @Test
    fun `a valid event is emitted for the caller's own party`() {
        val resp = resource().trackCreditEvent("""{"step":"FINANCING","action":"VIEWED"}""")

        assertThat(resp.status).isEqualTo(202)
        verify(exactly = 1) { funnel.emit(party, "FINANCING", "VIEWED") }
    }

    @Test
    fun `an unknown step is dropped, and the caller cannot tell`() {
        // 202 either way. A 400 would make the allow-list an oracle for what the bank tracks, and
        // telemetry has no business teaching a client anything.
        val resp = resource().trackCreditEvent("""{"step":"WHATEVER","action":"VIEWED"}""")

        assertThat(resp.status).isEqualTo(202)
        verify(exactly = 0) { funnel.emit(any(), any(), any()) }
    }

    @Test
    fun `an unknown action is dropped`() {
        resource().trackCreditEvent("""{"step":"QUOTE","action":"CONVERTED"}""")
        verify(exactly = 0) { funnel.emit(any(), any(), any()) }
    }

    @Test
    fun `a malformed body is dropped without a 5xx`() {
        val resp = resource().trackCreditEvent("not json")
        assertThat(resp.status).isEqualTo(202)
        verify(exactly = 0) { funnel.emit(any(), any(), any()) }
    }

    @Test
    fun `a party id in the body is ignored — the JWT decides whose journey this is`() {
        val someoneElse = UUID.randomUUID()
        resource().trackCreditEvent(
            """{"step":"FINANCING","action":"VIEWED","partyId":"$someoneElse"}""",
        )

        verify(exactly = 1) { funnel.emit(party, any(), any()) }
        verify(exactly = 0) { funnel.emit(someoneElse, any(), any()) }
    }

    @Test
    fun `the vocabulary cannot express a conversion`() {
        // Structural, and the point of the whole design: a funnel that can say "converted" is one
        // somebody will optimise, and optimising credit acceptance is what ADR-0269 exists to stop.
        val forbidden = listOf("CONVERT", "ACCEPT", "SOLD", "SIGNED")
        assertThat(CreditFunnelPublisher.VALID_ACTIONS).noneMatch { action ->
            forbidden.any { action.contains(it) }
        }
    }

    @Test
    fun `both allow-lists are small and closed — they are Prometheus labels`() {
        // An open vocabulary here is a cardinality bomb aimed at the metrics store, reachable by
        // any authenticated customer.
        assertThat(CreditFunnelPublisher.VALID_STEPS).hasSizeLessThan(10)
        assertThat(CreditFunnelPublisher.VALID_ACTIONS).hasSizeLessThan(15)
    }
}
