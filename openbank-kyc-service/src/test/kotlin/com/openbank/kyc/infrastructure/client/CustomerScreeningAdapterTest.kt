// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.CustomerScreeningRequest
import com.openbank.kyc.application.port.out.CustomerScreeningUnavailableException
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.SanctionsScreeningStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CustomerScreeningAdapterTest {

    private val client: SanctionsCustomerScreeningClient = mockk()
    private val request = CustomerScreeningRequest("Synthetic Name", "party-1", false, "idem-1")

    private fun adapter() = CustomerScreeningAdapter(client).also { it.self = it }

    private fun stub(response: CustomerScreenResponse) {
        every { client.screen(any()) } returns Uni.createFrom().item(response)
    }

    @Test
    fun `the call asks for the CUSTOMER profile and names the subject`(): Unit = runBlocking {
        val sent = slot<CustomerScreenRequest>()
        every { client.screen(capture(sent)) } returns
            Uni.createFrom().item(CustomerScreenResponse(status = "CLEAR", pepStatus = "CLEAR"))

        adapter().screenCustomer(request.copy(organisation = true))

        assertThat(sent.captured).isEqualTo(
            CustomerScreenRequest("idem-1", "ORGANIZATION", "Synthetic Name", "party-1", "CUSTOMER"),
        )
    }

    @Test
    fun `a sanctions hit and a clear PEP outcome are reported apart`(): Unit = runBlocking {
        stub(
            CustomerScreenResponse(
                status = "HIT",
                overallScore = 0.97,
                profile = "CUSTOMER",
                pepStatus = "CLEAR",
                matches = listOf(CustomerScreenMatch("OFAC_SDN", "SANCTIONS", "Synthetic Listed", 0.97)),
            ),
        )

        val result = adapter().screenCustomer(request)

        assertThat(result.sanctions.status).isEqualTo(SanctionsScreeningStatus.MATCH)
        assertThat(result.sanctions.matchedName).isEqualTo("Synthetic Listed")
        assertThat(result.pep.status).isEqualTo(PepScreeningStatus.CLEAR)
    }

    @Test
    fun `a PEP match does not become a sanctions match`(): Unit = runBlocking {
        stub(
            CustomerScreenResponse(
                status = "CLEAR",
                overallScore = 0.0,
                profile = "CUSTOMER",
                pepStatus = "HIT",
                matches = listOf(CustomerScreenMatch("PEP_GLOBAL", "PEP", "Synthetic Politician", 0.96)),
            ),
        )

        val result = adapter().screenCustomer(request)

        assertThat(result.sanctions.status).isEqualTo(SanctionsScreeningStatus.CLEAR)
        assertThat(result.sanctions.matchedName).isNull()
        assertThat(result.pep.status).isEqualTo(PepScreeningStatus.MATCH)
        assertThat(result.pep.matchedName).isEqualTo("Synthetic Politician")
        assertThat(result.pep.matchScore).isEqualTo(0.96)
    }

    @Test
    fun `a provider that ignores the profile is read conservatively`(): Unit = runBlocking {
        // No profile echo and no pepStatus: the provider counted the PEP name in `status`.
        stub(
            CustomerScreenResponse(
                status = "HIT",
                overallScore = 0.96,
                matches = listOf(
                    CustomerScreenMatch(
                        listType = "PEP_GLOBAL",
                        matchedName = "Synthetic Politician",
                        matchScore = 0.96,
                    ),
                ),
            ),
        )

        val result = adapter().screenCustomer(request)

        assertThat(result.sanctions.status).isEqualTo(SanctionsScreeningStatus.MATCH)
        assertThat(result.pep.status).isEqualTo(PepScreeningStatus.MATCH)
    }

    @Test
    fun `an unknown or missing status needs review, and an analyst clearance is clear`(): Unit = runBlocking {
        stub(CustomerScreenResponse(status = null, pepStatus = "SOMETHING_NEW"))
        val unknown = adapter().screenCustomer(request)
        assertThat(unknown.sanctions.status).isEqualTo(SanctionsScreeningStatus.POTENTIAL_MATCH)
        assertThat(unknown.pep.status).isEqualTo(PepScreeningStatus.POTENTIAL_MATCH)

        stub(CustomerScreenResponse(status = "WHITELISTED", pepStatus = "CLEAR"))
        assertThat(adapter().screenCustomer(request).sanctions.status).isEqualTo(SanctionsScreeningStatus.CLEAR)
    }

    @Test
    fun `a failed call is unavailable, never a result`() {
        every { client.screen(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy { runBlocking { adapter().screenCustomer(request) } }
            .isInstanceOf(CustomerScreeningUnavailableException::class.java)
    }
}
