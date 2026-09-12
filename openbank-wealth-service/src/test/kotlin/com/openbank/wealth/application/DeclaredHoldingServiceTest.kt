// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.wealth.application.port.`in`.DeclareHoldingCommand
import com.openbank.wealth.application.port.`in`.RevalueHoldingCommand
import com.openbank.wealth.application.port.out.DeclaredHoldingRepository
import com.openbank.wealth.application.port.out.HoldingNotFoundException
import com.openbank.wealth.application.usecase.DeclaredHoldingService
import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingDeclared
import com.openbank.wealth.domain.model.HoldingRevalued
import com.openbank.wealth.domain.model.HoldingType
import com.openbank.wealth.domain.model.HoldingWithdrawn
import com.openbank.wealth.domain.model.Valuation
import com.openbank.wealth.domain.model.ValuationSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class DeclaredHoldingServiceTest {

    private val fixedNow: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val repository = mockk<DeclaredHoldingRepository>()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val service = DeclaredHoldingService(repository, objectMapper, clock)

    private val party = UUID.randomUUID()

    private val valuation = Valuation(
        amount = BigDecimal("1000000.00"),
        currency = "CZK",
        valuedAt = LocalDate.of(2026, 9, 1),
        source = ValuationSource.CUSTOMER_DECLARED,
    )

    private fun command(reference: String? = null) = DeclareHoldingCommand(
        ownerPartyId = party,
        holdingType = HoldingType.REAL_ESTATE,
        label = "Flat, Prague 2",
        valuation = valuation,
        externalReference = reference,
    )

    private fun existing(reference: String?) = DeclaredHolding(
        id = UUID.randomUUID(),
        ownerPartyId = party,
        holdingType = HoldingType.REAL_ESTATE,
        label = "Flat, Prague 2",
        valuation = valuation,
        externalReference = reference,
        createdAt = fixedNow.minusSeconds(86400),
        updatedAt = fixedNow.minusSeconds(86400),
    )

    // Every test below declares an explicit `: Unit` return type on purpose: an inferred
    // non-Unit return makes JUnit5 silently ignore the method. The fleet gate that enforces this
    // greps test SOURCE TEXT, so this note deliberately does not spell out the offending idiom.
    @Test
    fun `declaring writes the row and the declared event together`(): Unit = runBlocking {
        val eventType = slot<String>()
        val payload = slot<String>()
        coEvery { repository.save(any(), capture(eventType), capture(payload)) } answers { firstArg() }

        val result = service.declare(command())

        assertThat(eventType.captured).isEqualTo(HoldingDeclared.EVENT_TYPE)
        val event = objectMapper.readValue(payload.captured, HoldingDeclared::class.java)
        assertThat(event.holdingId).isEqualTo(result.id)
        assertThat(event.valuationSource).isEqualTo(ValuationSource.CUSTOMER_DECLARED)
        // Recency, never isNotNull: an Instant.EPOCH default passes a non-null assertion, which is
        // how two fleet event types shipped stamped 1970 (#3882).
        assertThat(event.occurredAt).isEqualTo(fixedNow)
    }

    @Test
    fun `the declared event carries no itemised description of what the customer owns`(): Unit = runBlocking {
        val payload = slot<String>()
        coEvery { repository.findByNaturalKey(any(), any(), any()) } returns null
        coEvery { repository.save(any(), any(), capture(payload)) } answers { firstArg() }

        service.declare(command(reference = "LV-12345").copy(label = "Patek Philippe 5711"))

        // ADR-0301 D7 limits every downstream use to counts and totals, so neither the label nor
        // the external reference may reach a topic other services consume.
        assertThat(payload.captured).doesNotContain("Patek").doesNotContain("LV-12345")
    }

    @Test
    fun `a replayed declare with the same natural key returns the original and writes nothing`(): Unit = runBlocking {
        val original = existing("LV-12345")
        coEvery {
            repository.findByNaturalKey(party, HoldingType.REAL_ESTATE, "LV-12345")
        } returns original

        val result = service.declare(command(reference = "LV-12345"))

        assertThat(result.id).isEqualTo(original.id)
        assertThat(result.updatedAt).isEqualTo(original.updatedAt)
        coVerify(exactly = 0) { repository.save(any(), any(), any()) }
    }

    @Test
    fun `without an external reference no natural-key lookup happens and a row is written`(): Unit = runBlocking {
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }

        service.declare(command(reference = null))

        coVerify(exactly = 0) { repository.findByNaturalKey(any(), any(), any()) }
        coVerify(exactly = 1) { repository.save(any(), any(), any()) }
    }

    @Test
    fun `a blank external reference is treated as absent, not as a key`(): Unit = runBlocking {
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }

        val result = service.declare(command(reference = "   "))

        coVerify(exactly = 0) { repository.findByNaturalKey(any(), any(), any()) }
        assertThat(result.externalReference).isNull()
    }

    @Test
    fun `revaluing an unknown holding is a not-found, not a silent create`(): Unit = runBlocking {
        val unknown = UUID.randomUUID()
        coEvery { repository.findById(unknown) } returns null

        assertThatThrownBy {
            runBlocking { service.revalue(RevalueHoldingCommand(unknown, valuation)) }
        }.isInstanceOf(HoldingNotFoundException::class.java)

        coVerify(exactly = 0) { repository.save(any(), any(), any()) }
    }

    @Test
    fun `revaluing emits the revalued event with the new amount`(): Unit = runBlocking {
        val current = existing(null)
        coEvery { repository.findById(current.id) } returns current
        val payload = slot<String>()
        coEvery { repository.save(any(), HoldingRevalued.EVENT_TYPE, capture(payload)) } answers { firstArg() }

        val newValuation = valuation.copy(amount = BigDecimal("2500000.00"))
        service.revalue(RevalueHoldingCommand(current.id, newValuation))

        val event = objectMapper.readValue(payload.captured, HoldingRevalued::class.java)
        assertThat(event.amount).isEqualByComparingTo("2500000.00")
        assertThat(event.occurredAt).isEqualTo(fixedNow)
    }

    @Test
    fun `withdrawing emits the withdrawn event`(): Unit = runBlocking {
        val current = existing(null)
        coEvery { repository.findById(current.id) } returns current
        val payload = slot<String>()
        coEvery { repository.save(any(), HoldingWithdrawn.EVENT_TYPE, capture(payload)) } answers { firstArg() }

        service.withdraw(current.id)

        val event = objectMapper.readValue(payload.captured, HoldingWithdrawn::class.java)
        assertThat(event.holdingId).isEqualTo(current.id)
    }
}
