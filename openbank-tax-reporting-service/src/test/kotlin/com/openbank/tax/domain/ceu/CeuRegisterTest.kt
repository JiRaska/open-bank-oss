// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.ceu

import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CeuRegisterTest {
    private val mon = LocalDate.parse("2026-11-02")
    private val tue = LocalDate.parse("2026-11-03")
    private val wed = LocalDate.parse("2026-11-04")
    private val t0 = Instant.parse("2026-11-02T09:00:00Z")
    private val acc = UUID.randomUUID()
    private val holder = UUID.randomUUID()
    private val disposer = UUID.randomUUID()

    private fun opened(id: UUID = acc, on: LocalDate = mon, at: Instant = t0, synthetic: Boolean = false) =
        RegisterFact.AccountOpened(UUID.randomUUID(), id, "123-$id", "CZK", on, at, synthetic)

    private fun tie(
        relation: UUID,
        party: UUID,
        role: CeuRole,
        on: LocalDate = mon,
        at: Instant = t0,
        synthetic: Boolean = false,
        account: UUID = acc,
    ) = RegisterFact.RelationStarted(UUID.randomUUID(), relation, account, party, role, on, at, synthetic)

    private fun window(day: LocalDate) = ReportingWindow.forReportingDay(day, day.minusDays(1))

    @Test
    fun `initial full load reports every real account as NEW with its persons`() {
        val facts = listOf(opened(), tie(acc, holder, CeuRole.HOLDER))
        val now = RegisterSnapshot.fold(facts, mon, t0)

        val overview = ChangeOverview.diff(window(tue), RegisterSnapshot.empty(mon, t0), now)

        assertThat(overview.initialLoad).isTrue()
        assertThat(overview.changes).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ChangeKind.NEW)
            assertThat(it.account.persons.map { p -> p.role }).containsExactly(CeuRole.HOLDER)
        })
    }

    @Test
    fun `an unchanged account produces no record and a new disposer produces CHANGED`() {
        val base = listOf(opened(), tie(acc, holder, CeuRole.HOLDER))
        val reported = RegisterSnapshot.fold(base, mon, t0)
        val later = Instant.parse("2026-11-03T08:00:00Z")

        assertThat(ChangeOverview.diff(window(wed), reported, RegisterSnapshot.fold(base, tue, later)).changes)
            .isEmpty()

        val withDisposer = base + tie(UUID.randomUUID(), disposer, CeuRole.DISPOSER, on = tue, at = later)
        val overview = ChangeOverview.diff(window(wed), reported, RegisterSnapshot.fold(withDisposer, tue, later))

        assertThat(overview.changes).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ChangeKind.CHANGED)
            assertThat(it.account.persons.map { p -> p.partyId }).containsExactlyInAnyOrder(holder, disposer)
        })
    }

    @Test
    fun `an ended relation drops the person and a closing fact reports CLOSED`() {
        val grant = UUID.randomUUID()
        val later = Instant.parse("2026-11-03T08:00:00Z")
        val base = listOf(opened(), tie(acc, holder, CeuRole.HOLDER), tie(grant, disposer, CeuRole.DISPOSER))
        val reported = RegisterSnapshot.fold(base, mon, t0)

        val ended = base + RegisterFact.RelationEnded(UUID.randomUUID(), grant, acc, tue, later, false)
        assertThat(RegisterSnapshot.fold(ended, tue, later).accounts.getValue(acc).persons.map { it.partyId })
            .containsExactly(holder)

        val closed = ended + RegisterFact.AccountClosed(UUID.randomUUID(), acc, tue, later, false)
        val overview = ChangeOverview.diff(window(wed), reported, RegisterSnapshot.fold(closed, tue, later))
        assertThat(overview.changes).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ChangeKind.CLOSED)
            assertThat(it.account.closedOn).isEqualTo(tue)
        })
    }

    @Test
    fun `synthetic accounts and synthetic ties are excluded and counted`() {
        val synthAcc = UUID.randomUUID()
        val facts = listOf(
            opened(),
            tie(acc, holder, CeuRole.HOLDER),
            tie(UUID.randomUUID(), disposer, CeuRole.DISPOSER, synthetic = true),
            opened(id = synthAcc, synthetic = true),
            tie(synthAcc, UUID.randomUUID(), CeuRole.HOLDER, account = synthAcc, synthetic = true),
        )

        val snapshot = RegisterSnapshot.fold(facts, mon, t0)
        val overview = ChangeOverview.diff(window(tue), RegisterSnapshot.empty(mon, t0), snapshot)

        assertThat(snapshot.accounts.keys).containsExactly(acc)
        assertThat(snapshot.accounts.getValue(acc).persons.map { it.partyId }).containsExactly(holder)
        assertThat(overview.droppedSyntheticAccounts).isEqualTo(1)
        // The synthetic account's own holder tie drops with its account, so only the tie on the
        // REAL account counts as a dropped relation.
        assertThat(overview.droppedSyntheticRelations).isEqualTo(1)
        assertThat(overview.changes.map { it.account.accountId }).doesNotContain(synthAcc)
    }

    @Test
    fun `a late fact for an already reported day surfaces in the next overview`() {
        val reportedAt = Instant.parse("2026-11-03T09:00:00Z")
        val lateAt = Instant.parse("2026-11-03T15:00:00Z")
        // Disposer valid from Monday, but learnt only after Tuesday's filing was assembled.
        val facts = listOf(
            opened(),
            tie(acc, holder, CeuRole.HOLDER),
            tie(UUID.randomUUID(), disposer, CeuRole.DISPOSER, on = mon, at = lateAt),
        )

        val reported = RegisterSnapshot.fold(facts, mon, reportedAt)
        assertThat(reported.accounts.getValue(acc).persons).hasSize(1)

        val overview = ChangeOverview.diff(window(wed), reported, RegisterSnapshot.fold(facts, tue, lateAt))
        assertThat(overview.changes).singleElement().extracting { it.kind }.isEqualTo(ChangeKind.CHANGED)
    }

    @Test
    fun `an account that disappears without a closing fact is reported CLOSED, never silently dropped`() {
        val reported = RegisterSnapshot.fold(listOf(opened()), mon, t0)
        val overview = ChangeOverview.diff(window(wed), reported, RegisterSnapshot.empty(tue, t0))

        assertThat(overview.changes).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ChangeKind.CLOSED)
            assertThat(it.account.closedOn).isEqualTo(tue)
        })
    }

    @Test
    fun `facts are not visible before their valid day or their record time`() {
        val facts = listOf(opened(on = tue, at = Instant.parse("2026-11-03T08:00:00Z")))
        assertThat(RegisterSnapshot.fold(facts, mon, Instant.parse("2026-11-05T00:00:00Z")).accounts).isEmpty()
        assertThat(RegisterSnapshot.fold(facts, wed, t0).accounts).isEmpty()
        assertThat(RegisterSnapshot.fold(facts, wed, Instant.parse("2026-11-05T00:00:00Z")).accounts).hasSize(1)
    }

    @Test
    fun `Monday window covers Friday to Sunday`() {
        val monday = LocalDate.parse("2026-11-09")
        val w = ReportingWindow.forReportingDay(monday, LocalDate.parse("2026-11-06"))
        assertThat(w.from).isEqualTo(LocalDate.parse("2026-11-06"))
        assertThat(w.to).isEqualTo(LocalDate.parse("2026-11-08"))
        assertThatThrownBy { ReportingWindow(monday, monday, monday) }.isInstanceOf(TaxValidationException::class.java)
    }
}
