// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.ceu

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.domain.ceu.CeuRole
import com.openbank.tax.domain.ceu.RegisterFact
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnValidationException
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.infrastructure.returns.CatalogueParser
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class CeuReturnDataAdapterTest {
    private class Register : CeuRegisterRepository {
        val facts = mutableListOf<RegisterFact>()
        val synthetic = mutableSetOf<UUID>()

        override suspend fun append(fact: RegisterFact) = facts.add(fact)

        override suspend fun startOf(relationId: UUID) = null

        override suspend fun classifyParty(partyId: UUID, synthetic: Boolean, recordedAt: Instant) = true

        override suspend fun syntheticParties(knownAt: Instant) = synthetic

        override suspend fun factsUpTo(validOn: LocalDate, knownAt: Instant) = facts.toList()
    }

    private class Returns : StatutoryReturnRepository {
        val stored = mutableListOf<StatutoryReturn>()

        override suspend fun insert(statutoryReturn: StatutoryReturn) = statutoryReturn.also { stored += it }

        override suspend fun findReturn(id: UUID) = stored.firstOrNull { it.id == id }

        override suspend fun latestRevision(
            catalogueId: String,
            returnCode: String,
            entityId: String,
            period: ReportingPeriod,
        ) = stored.filter { it.period == period }.maxByOrNull { it.revision }

        override suspend fun listReturns() = stored.toList()

        override suspend fun save(statutoryReturn: StatutoryReturn, expectedVersion: Long) = statutoryReturn
    }

    private val catalogue = CatalogueParser.parse(ObjectMapper(), "statutory-returns/cz/ceu.v1.json")
    private val definition = catalogue.definition("CEUSIFE10")!!
    private val register = Register()
    private val returns = Returns()
    private val monday = ReportingPeriod(Periodicity.WORKING_DAY, LocalDate.parse("2026-11-08"))
    private val tuesday = ReportingPeriod(Periodicity.WORKING_DAY, LocalDate.parse("2026-11-09"))

    private fun adapter(now: String) =
        CeuReturnDataAdapter(register, returns, Clock.fixed(Instant.parse(now), ZoneOffset.UTC))

    private fun open(account: UUID, holder: UUID, on: String, at: String, synthetic: Boolean = false) {
        val day = LocalDate.parse(on)
        val instant = Instant.parse(at)
        register.facts +=
            RegisterFact.AccountOpened(UUID.randomUUID(), account, "acc-$account", "CZK", day, instant, synthetic)
        register.facts += RegisterFact.RelationStarted(
            UUID.randomUUID(),
            UUID.randomUUID(),
            account,
            holder,
            CeuRole.HOLDER,
            day,
            instant,
            synthetic,
        )
    }

    private suspend fun assembleAndFile(period: ReportingPeriod, now: String): StatutoryReturn {
        val data = adapter(now).fetch(catalogue, definition, "company", period)
        val r = StatutoryReturn.assemble(
            catalogue, definition, "company", period, 1, data.values, data.lines, "system:ceu", Instant.parse(now),
        ).approve("alice", Instant.parse(now)).submit("SDAT", "alice", Instant.parse(now))
        returns.insert(r)
        return r
    }

    @Test
    fun `first overview is the initial full load, synthetic data excluded and counted`(): Unit = runBlocking {
        val real = UUID.randomUUID()
        val canaryParty = UUID.randomUUID()
        open(real, UUID.randomUUID(), "2026-11-06", "2026-11-06T10:00:00Z")
        open(UUID.randomUUID(), UUID.randomUUID(), "2026-11-06", "2026-11-06T10:00:00Z", synthetic = true)
        open(UUID.randomUUID(), canaryParty, "2026-11-06", "2026-11-06T10:00:00Z")
        register.synthetic += canaryParty

        val data = adapter("2026-11-09T05:00:00Z").fetch(catalogue, definition, "company", monday)

        assertThat(data.values.getValue("initial_load")).isEqualByComparingTo("1")
        assertThat(data.values.getValue("changes_new")).isEqualByComparingTo("1")
        assertThat(data.values.getValue("synthetic_accounts_dropped")).isEqualByComparingTo("2")
        assertThat(data.lines.map { it.fields["account_id"] }).containsExactly(real.toString())
        // The assembled figures satisfy the catalogue's own rules: this is what reaches four-eyes.
        assertThat(definition.validate(data.values)).isEmpty()
    }

    @Test
    fun `the next overview diffs against what was filed, including a late fact`(): Unit = runBlocking {
        open(UUID.randomUUID(), UUID.randomUUID(), "2026-11-06", "2026-11-06T10:00:00Z")
        assembleAndFile(monday, "2026-11-09T05:00:00Z")
        // Opened on Sunday (already reported window) but learnt only after Monday's filing.
        val late = UUID.randomUUID()
        open(late, UUID.randomUUID(), "2026-11-08", "2026-11-09T09:00:00Z")

        val data = adapter("2026-11-10T05:00:00Z").fetch(catalogue, definition, "company", tuesday)

        assertThat(data.values.getValue("initial_load")).isEqualByComparingTo("0")
        assertThat(data.lines.map { it.fields["account_id"] }).containsExactly(late.toString())
    }

    @Test
    fun `a rejected predecessor is no baseline, so its changes roll forward`(): Unit = runBlocking {
        val account = UUID.randomUUID()
        open(account, UUID.randomUUID(), "2026-11-06", "2026-11-06T10:00:00Z")
        val filed = assembleAndFile(monday, "2026-11-09T05:00:00Z")
        returns.stored.replaceAll {
            if (it.id ==
                filed.id
            ) {
                it.acknowledge(false, "rejected", "bob", Instant.now())
            } else {
                it
            }
        }

        val data = adapter("2026-11-10T05:00:00Z").fetch(catalogue, definition, "company", tuesday)

        assertThat(data.values.getValue("initial_load")).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(data.lines.map { it.fields["account_id"] }).containsExactly(account.toString())
    }

    @Test
    fun `an open account without a holder fails CEU validation before anything is stored`(): Unit = runBlocking {
        register.facts += RegisterFact.AccountOpened(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "acc-x",
            "CZK",
            LocalDate.parse("2026-11-06"),
            Instant.parse("2026-11-06T10:00:00Z"),
            false,
        )
        val e = assertThrows<ReturnValidationException> {
            adapter("2026-11-09T05:00:00Z").fetch(catalogue, definition, "company", monday)
        }
        assertThat(e.findings.map { it.ruleId }).containsExactly("CEU-HOLDER")
    }
}
