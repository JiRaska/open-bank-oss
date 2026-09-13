// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.AccountLookupPort
import com.openbank.sepa.application.port.out.AmlCaseRiskLevel
import com.openbank.sepa.application.port.out.OpenAmlCaseCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AmlCaseAdapterTest {

    private val client: AmlServiceClient = mockk()

    /** The party that actually owns the debtor account — a DIFFERENT id, which is the whole point. */
    private val resolvedParty: UUID = UUID.randomUUID()

    private fun adapter(party: UUID? = resolvedParty): AmlCaseAdapter {
        val accounts = object : AccountLookupPort {
            override suspend fun findPartyByAccountId(accountId: UUID): UUID? = party
        }
        return AmlCaseAdapter(client).also {
            it.self = it
            it.accounts = accounts
        }
    }

    private fun command(matchedEntity: String? = "Sanctioned Co") = OpenAmlCaseCommand(
        idempotencyKey = "aml-key-1",
        paymentId = UUID.randomUUID(),
        debtorAccountId = UUID.randomUUID(),
        customerReference = "Payer / 1000/0800",
        riskLevel = AmlCaseRiskLevel.CRITICAL,
        alertCode = "SANCTIONS_HIT",
        alertDetail = "creditor on list",
        matchedEntity = matchedEntity,
    )

    @Test
    fun `openCase maps the command onto the aml-service contract`(): Unit = runBlocking {
        val cmd = command()
        val keySlot = slot<String>()
        val bodySlot = slot<CreateAmlCaseRequest>()
        val response = mockk<Response>()
        every { client.createCase(capture(keySlot), capture(bodySlot)) } returns
            Uni.createFrom().item(response)

        adapter().openCase(cmd)

        verify(exactly = 1) { client.createCase(any(), any()) }
        assertThat(keySlot.captured).isEqualTo("aml-key-1")
        val body = bodySlot.captured
        // This used to assert `partyId == cmd.debtorAccountId`, i.e. it PINNED the defect: an
        // account id in party_id, which no join to the party register can resolve (#8505, the
        // SEPA twin of the domestic rail's #3274).
        assertThat(body.partyId)
            .describedAs("the case must carry the party that owns the debtor account, not the account")
            .isEqualTo(resolvedParty)
            .isNotEqualTo(cmd.debtorAccountId)
        assertThat(body.accountId).isEqualTo(cmd.debtorAccountId)
        assertThat(body.transactionId).isEqualTo(cmd.paymentId)
        assertThat(body.customerReference).isEqualTo("Payer / 1000/0800")
        assertThat(body.screeningType).isEqualTo("TRANSACTION_MONITORING")
        assertThat(body.riskLevel).isEqualTo("CRITICAL")
        assertThat(body.alertCode).isEqualTo("SANCTIONS_HIT")
        assertThat(body.alertDetail).isEqualTo("creditor on list")
        assertThat(body.matchedEntity).isEqualTo("Sanctioned Co")
    }

    @Test
    fun `openCase forwards a null matched entity unchanged`(): Unit = runBlocking {
        val bodySlot = slot<CreateAmlCaseRequest>()
        val response = mockk<Response>()
        every { client.createCase(any(), capture(bodySlot)) } returns Uni.createFrom().item(response)

        adapter().openCase(command(matchedEntity = null))

        assertThat(bodySlot.captured.matchedEntity).isNull()
    }

    @Test
    fun `an unresolvable party still opens the case, with the account id and a loud warning`(): Unit = runBlocking {
        // Losing an AML case over an account-service outage would be worse than an imprecise one,
        // so the fallback stays — but it is logged, so those rows are identifiable rather than
        // indistinguishable from correctly-resolved ones (#8505).
        val cmd = command()
        val bodySlot = slot<CreateAmlCaseRequest>()
        every { client.createCase(any(), capture(bodySlot)) } returns
            Uni.createFrom().item(mockk<Response>())

        adapter(party = null).openCase(cmd)

        assertThat(bodySlot.captured.partyId).isEqualTo(cmd.debtorAccountId)
        assertThat(bodySlot.captured.accountId).isEqualTo(cmd.debtorAccountId)
    }
}
