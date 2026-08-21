// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

private class TransientDbFailure : RuntimeException("connection refused")

/**
 * This consumer had NO tests, which is part of how #5698 stayed invisible: it is the second half of
 * the activation gate (kyc-service opens and approves the case, party-service applies the decision
 * and flips the party to ACTIVE), and both halves swallowed their failures.
 */
class KycAmlEventConsumerTest {

    private val partyUseCase = mockk<PartyUseCase>()
    private val consumer = KycAmlEventConsumer(partyUseCase, ObjectMapper())

    @Test
    fun `KYC_CASE_APPROVED applies the decision to the party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) } returns mockk()

        consumer.consumeKyc("""{"eventType":"KYC_CASE_APPROVED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) }
    }

    /**
     * The one that matters. A swallowed failure here acks the approval, so the party never reaches
     * ACTIVE — its accounts stay PENDING_ACTIVATION and the welcome bonus never fires. Same
     * customer-visible outcome as #5698, one service further down the funnel.
     */
    @Test
    fun `a persistent failure applying a KYC decision is RETHROWN so the connector dead-letters`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) } throws TransientDbFailure()

        assertThrows<TransientDbFailure> {
            runBlocking { consumer.consumeKyc("""{"eventType":"KYC_CASE_APPROVED","partyId":"$partyId"}""") }
        }

        coVerify(exactly = 3) { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) }
    }

    @Test
    fun `a transient failure applying a KYC decision is retried and then applied`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        var calls = 0
        coEvery { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) } answers {
            calls++
            if (calls == 1) throw TransientDbFailure() else mockk()
        }

        consumer.consumeKyc("""{"eventType":"KYC_CASE_APPROVED","partyId":"$partyId"}""")

        coVerify(exactly = 2) { partyUseCase.updateKycStatus(partyId, KycStatus.APPROVED) }
    }

    @Test
    fun `a persistent failure applying an AML decision is RETHROWN`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { partyUseCase.updateAmlStatus(partyId, AmlStatus.CLEARED) } throws TransientDbFailure()

        assertThrows<TransientDbFailure> {
            runBlocking {
                consumer.consumeAml(
                    """{"eventType":"aml.case.status_changed.v1","partyId":"$partyId","newStatus":"CLEARED"}""",
                )
            }
        }

        coVerify(exactly = 3) { partyUseCase.updateAmlStatus(partyId, AmlStatus.CLEARED) }
    }

    @Test
    fun `an unparseable payload is acked — it is the real poison pill`(): Unit = runBlocking {
        consumer.consumeKyc("not json")
        consumer.consumeAml("not json")

        coVerify(exactly = 0) { partyUseCase.updateKycStatus(any(), any()) }
        coVerify(exactly = 0) { partyUseCase.updateAmlStatus(any(), any()) }
    }

    @Test
    fun `a non-terminal KYC event is ignored without touching the party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()

        consumer.consumeKyc("""{"eventType":"KYC_CASE_OPENED","partyId":"$partyId"}""")

        coVerify(exactly = 0) { partyUseCase.updateKycStatus(any(), any()) }
    }
}
