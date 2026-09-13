// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.PayeeLimitExceededException
import com.openbank.party.application.port.`in`.SavePayeeCommand
import com.openbank.party.application.port.out.PartyPayeeRepository
import com.openbank.party.domain.model.Payee
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Saved payees (TOP-10 #5) — server side of the mobile app's device-local PayeeStore. Split into
 * its own class to keep [PartyServiceTest] under detekt's LargeClass threshold.
 */
class PartyServicePayeeTest {

    private val now = Instant.parse("2026-08-16T12:00:00Z")
    private val partyId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private fun newService(payeeRepo: PartyPayeeRepository) = PartyService().apply {
        this.payeeRepo = payeeRepo
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    @Test
    fun `savePayee normalises the IBAN, trims free text, and stamps createdAt from the clock`(): Unit = runBlocking {
        val repo = mockk<PartyPayeeRepository>()
        coEvery { repo.findByPartyId(partyId) } returns emptyList()
        val saved = slot<Payee>()
        coEvery { repo.save(capture(saved)) } answers { firstArg() }

        val result = newService(repo).savePayee(
            SavePayeeCommand(
                partyId = partyId,
                name = "  Jana Nováková  ",
                iban = "cz65 0800 0000 1920 0014 5399",
                bic = null,
            ),
        )

        assertThat(saved.captured.iban).isEqualTo("CZ6508000000192000145399")
        assertThat(saved.captured.name).isEqualTo("Jana Nováková")
        assertThat(saved.captured.createdAt).isEqualTo(now)
        assertThat(result.partyId).isEqualTo(partyId)
    }

    @Test
    fun `savePayee reuses the existing id and stays within the 30-payee cap when re-saving the same IBAN`(): Unit =
        runBlocking {
            val repo = mockk<PartyPayeeRepository>()
            val existingId = UUID.randomUUID()
            val existing = (1..30).map { i ->
                Payee(
                    id = if (i == 1) existingId else UUID.randomUUID(),
                    partyId = partyId,
                    name = "Payee $i",
                    iban = if (i == 1) "CZ6508000000192000145399" else "CZ650800000019200014530$i",
                    bic = null,
                    createdAt = now.minusSeconds(i.toLong()),
                )
            }
            coEvery { repo.findByPartyId(partyId) } returns existing
            val saved = slot<Payee>()
            coEvery { repo.save(capture(saved)) } answers { firstArg() }

            newService(repo).savePayee(
                SavePayeeCommand(
                    partyId = partyId,
                    name = "Jana Nováková",
                    iban = "CZ6508000000192000145399",
                    bic = null,
                ),
            )

            // A re-save of an already-saved IBAN reuses that payee's id — it never creates a
            // 31st row, so it cannot itself trip the cap.
            assertThat(saved.captured.id).isEqualTo(existingId)
        }

    @Test
    fun `savePayee refuses a genuinely new payee once the party already has 30`() {
        val repo = mockk<PartyPayeeRepository>()
        val existing = (1..30).map { i ->
            Payee(
                id = UUID.randomUUID(),
                partyId = partyId,
                name = "Payee $i",
                iban = "CZ650800000019200014530$i",
                bic = null,
                createdAt = now.minusSeconds(i.toLong()),
            )
        }
        coEvery { repo.findByPartyId(partyId) } returns existing

        assertThatThrownBy {
            runBlocking {
                newService(repo).savePayee(
                    SavePayeeCommand(
                        partyId = partyId,
                        name = "One too many",
                        iban = "CZ9999999999999999999999",
                        bic = null,
                    ),
                )
            }
        }.isInstanceOf(PayeeLimitExceededException::class.java)

        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `deletePayee normalises the IBAN the same way savePayee does`(): Unit = runBlocking {
        val repo = mockk<PartyPayeeRepository>()
        coEvery { repo.deleteByPartyIdAndIban(partyId, "CZ6508000000192000145399") } returns Unit

        newService(repo).deletePayee(partyId, "cz65 0800 0000 1920 0014 5399")

        coVerify(exactly = 1) { repo.deleteByPartyIdAndIban(partyId, "CZ6508000000192000145399") }
    }
}
