// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountNumberReissue
import com.openbank.account.application.port.out.AccountNumberReissueRepository
import com.openbank.account.application.port.out.StoredAccountNumber
import com.openbank.account.domain.event.AccountNumberReissuedEvent
import com.openbank.account.domain.model.CzechIbanValidity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.money.CurrencyCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AccountNumberReissueServiceTest {

    private val generator = IbanGenerator("0000")
    private val clock = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC)

    /** In-memory store with the same compare-and-set the real one has. */
    private class FakeStore(rows: Map<UUID, String>) : AccountNumberReissueRepository {
        val numbers = rows.toMutableMap()
        val audit = mutableListOf<AccountNumberReissue>()
        val events = mutableListOf<DomainEvent>()
        var failFor: UUID? = null

        override suspend fun pageAccountNumbers(afterId: UUID?, limit: Int) = numbers.entries
            .sortedBy { it.key }.filter { afterId == null || it.key > afterId }.take(limit)
            .map { StoredAccountNumber(it.key, it.value) }

        override suspend fun accountNumberTaken(accountNumber: String) = accountNumber in numbers.values

        override suspend fun reissue(reissue: AccountNumberReissue, event: (Long) -> DomainEvent): Boolean {
            if (reissue.accountId == failFor) error("version conflict")
            if (numbers[reissue.accountId] != reissue.oldAccountNumber) return false
            numbers[reissue.accountId] = reissue.newAccountNumber
            audit += reissue
            events += event(2L)
            return true
        }
    }

    private val goodNumber = generator.generate(CurrencyCode.of("CZK")).value
    private val bad1 = "CZ0000000000000000000002"
    private val bad2 = "CZ0000000000000000000003"

    private fun service(store: FakeStore) = AccountNumberReissueService(store, generator, clock)

    @Test
    fun `re-issues only the invalid numbers, audits old to new and emits one event each`(): Unit = runBlocking {
        val good = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val a = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val b = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val store = FakeStore(mapOf(good to goodNumber, a to bad1, b to bad2))

        val report = service(store).reissueInvalid()

        assertThat(report).isEqualTo(ReissueReport(scanned = 2, reissued = 2, skipped = 0, failed = 0, remaining = 0))
        assertThat(store.numbers[good]).isEqualTo(goodNumber)
        assertThat(store.numbers.values).allMatch { CzechIbanValidity.isNationallyValid(it) }
        assertThat(store.audit.map { it.oldAccountNumber }).containsExactlyInAnyOrder(bad1, bad2)
        assertThat(store.audit.map { it.newAccountNumber }).isEqualTo(store.audit.map { store.numbers[it.accountId] })
        val event = store.events.filterIsInstance<AccountNumberReissuedEvent>().single { it.aggregateId == a }
        assertThat(event.previousAccountNumber).isEqualTo(bad1)
        assertThat(event.accountNumber).isEqualTo(store.numbers[a])
        assertThat(event.eventType).isEqualTo("AccountNumberReissued")
        assertThat(event.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `a second pass is a no-op`(): Unit = runBlocking {
        val store = FakeStore(mapOf(UUID.randomUUID() to bad1))
        service(store).reissueInvalid()
        val second = service(store).reissueInvalid()

        assertThat(second).isEqualTo(ReissueReport(0, 0, 0, 0, 0))
        assertThat(store.audit).hasSize(1)
        assertThat(store.events).hasSize(1)
    }

    @Test
    fun `one failing account does not stop the others and stays counted as remaining`(): Unit = runBlocking {
        val a = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val b = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val store = FakeStore(mapOf(a to bad1, b to bad2)).also { it.failFor = a }

        val report = service(store).reissueInvalid()

        assertThat(report.failed).isEqualTo(1)
        assertThat(report.reissued).isEqualTo(1)
        assertThat(report.remaining).isEqualTo(1)
        assertThat(store.numbers[a]).isEqualTo(bad1)
    }

    @Test
    fun `an account re-issued by another pod first is skipped, not overwritten`(): Unit = runBlocking {
        val a = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val store = object : AccountNumberReissueRepository by FakeStore(mapOf(a to bad1)) {
            override suspend fun reissue(reissue: AccountNumberReissue, event: (Long) -> DomainEvent) = false
        }
        val report = AccountNumberReissueService(store, generator, clock).reissueInvalid()

        assertThat(report.skipped).isEqualTo(1)
        assertThat(report.reissued).isEqualTo(0)
    }

    @Test
    fun `a generated number already held by another account is never used`(): Unit = runBlocking {
        val holder = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val victim = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val store = FakeStore(mapOf(holder to goodNumber, victim to bad1))
        val spare = generator.generate(CurrencyCode.of("CZK"))
        val gen = io.mockk.mockk<IbanGenerator>()
        // First candidate collides with the holder's number; the second is free.
        io.mockk.every { gen.generate(any()) } returnsMany listOf(
            com.openbank.libs.domain.account.Iban.of(goodNumber),
            spare,
        )

        val report = AccountNumberReissueService(store, gen, clock).reissueInvalid()

        assertThat(report.reissued).isEqualTo(1)
        assertThat(store.numbers[victim]).isEqualTo(spare.value)
        assertThat(store.numbers[holder]).isEqualTo(goodNumber)
        assertThat(store.numbers.values).doesNotHaveDuplicates()
    }
}
