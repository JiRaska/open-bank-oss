// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class GsftBatchTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val rates = FxRates(day, mapOf("EUR" to BigDecimal("25.000")))
    private val cp1250 = Charset.forName("windows-1250")
    private val header = BatchHeader("6363", 1, LocalDateTime.of(2026, 11, 10, 8, 30, 0), BatchMode.TEST)

    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun identity(id: UUID, idc: String, name: String = "Jana Nováková, ml.") = DepositorIdentity(
        partyId = id,
        idc = idc,
        type = EntitledPersonType.NATURAL_PERSON,
        name = name,
        street = "Na Příkopě 28",
        city = "Praha",
        zip = "11000",
        country = "CZ",
        language = "cs",
    )

    private fun account(iban: String, principal: String, vararg holders: UUID, currency: String = "CZK") =
        DepositAccount(
            UUID.randomUUID(),
            iban,
            currency,
            BigDecimal(principal),
            BigDecimal.ZERO,
            holders.map { Holding(it, null) },
            false,
        )

    private fun lines(batch: GsftBatch, prefix: String) =
        String(batch.files.getValue("${prefix}6363.00001"), cp1250).split("\r\n").filter { it.isNotEmpty() }

    private fun render(accounts: List<DepositAccount>, ids: List<DepositorIdentity>): GsftBatch {
        val people = ids.map { Depositor(it.partyId, null, false) }
        val assembly = InsuredClaims.assemble(day, people, accounts, rates)
        return GsftBatchRenderer.render(assembly, accounts, ids, rates, header)
    }

    @Test
    fun `names files per the decree and omits a file with no records`() {
        val batch =
            render(listOf(account("CZ0100000000000000000001", "1000", alice)), listOf(identity(alice, "8001010009")))
        assertThat(batch.files.keys).containsExactly("DIW6363.00001", "DIC6363.00001", "DIA6363.00001")
    }

    @Test
    fun `writes the DIW header with counts, total payment and the TEST marker`() {
        val batch =
            render(listOf(account("CZ0100000000000000000001", "1234.56", alice)), listOf(identity(alice, "8001010009")))
        assertThat(lines(batch, "DIW").single()).isEqualTo(
            "R,20261110083000,00001,000000001,000000001,000000000,000000000,000000000,000000000,000000000123456,1,TEST",
        )
    }

    @Test
    fun `quotes a text containing a comma and encodes CP-1250`() {
        val batch =
            render(listOf(account("CZ0100000000000000000001", "10", alice)), listOf(identity(alice, "8001010009")))
        val dic = lines(batch, "DIC").single()
        assertThat(dic).startsWith("E,8001010009,F,00001,1,\"Jana Nováková, ml.\",Na Příkopě 28,Praha,11000,CZ,")
        assertThat(dic).endsWith(",000000000001000,000000000001000,,,,,cs")
        assertThat(String(batch.files.getValue("DIC6363.00001"), cp1250)).doesNotEndWith(",\r\n")
    }

    @Test
    fun `a joint account appears in DIA under each owner with the total and in DIL as shares`() {
        val joint = account("CZ0100000000000000000002", "1000", alice, bob)
        val batch =
            render(listOf(joint), listOf(identity(alice, "8001010009"), identity(bob, "8501010006", "Petr Novák")))
        assertThat(lines(batch, "DIA")).allSatisfy { assertThat(it).endsWith(",000000000100000,000000000100000") }
        assertThat(lines(batch, "DIA")).hasSize(2)
        assertThat(lines(batch, "DIL")).hasSize(2).allSatisfy {
            assertThat(it).endsWith(",000000000050000,000000000050000")
        }
        assertThat(lines(batch, "DIC")).allSatisfy { assertThat(it).contains(",000000000050000,000000000050000,") }
    }

    @Test
    fun `reports the foreign-currency balance in account currency and in CZK`() {
        val batch = render(
            listOf(account("CZ0100000000000000000003", "100", alice, currency = "EUR")),
            listOf(identity(alice, "8001010009")),
        )
        assertThat(lines(batch, "DIA").single()).endsWith(",EUR,000000000010000,000000000250000")
    }

    @Test
    fun `the LIVE batch carries KEEN`() {
        val accounts = listOf(account("CZ0100000000000000000001", "1", alice))
        val ids = listOf(identity(alice, "8001010009"))
        val assembly = InsuredClaims.assemble(day, listOf(Depositor(alice, null, false)), accounts, rates)
        val batch = GsftBatchRenderer.render(assembly, accounts, ids, rates, header.copy(mode = BatchMode.LIVE))
        assertThat(lines(batch, "DIW").single()).endsWith(",KEEN")
    }

    @Test
    fun `a person with no identity is rejected without stopping the batch`() {
        val accounts =
            listOf(account("CZ0100000000000000000001", "1", alice), account("CZ0100000000000000000002", "2", bob))
        val people = listOf(Depositor(alice, null, false), Depositor(bob, null, false))
        val assembly = InsuredClaims.assemble(day, people, accounts, rates)
        val batch = GsftBatchRenderer.render(assembly, accounts, listOf(identity(bob, "8501010006")), rates, header)
        assertThat(batch.rejected.map { it.partyId }).containsExactly(alice)
        assertThat(lines(batch, "DIC")).hasSize(1)
        assertThat(lines(batch, "DIW").single()).contains(",000000000000200,")
    }

    @Test
    fun `a character CP-1250 cannot encode rejects that person only`() {
        val batch = render(
            listOf(account("CZ0100000000000000000001", "1", alice), account("CZ0100000000000000000002", "2", bob)),
            listOf(identity(alice, "8001010009", "名前"), identity(bob, "8501010006", "Petr Novák")),
        )
        assertThat(batch.rejected.single().reason).contains("CP-1250")
        assertThat(lines(batch, "DIC")).hasSize(1).allSatisfy { assertThat(it).contains("Petr Novák") }
    }

    @Test
    fun `an over-long name rejects that person only`() {
        val batch =
            render(
                listOf(account("CZ0100000000000000000001", "1", alice)),
                listOf(identity(alice, "8001010009", "x".repeat(241))),
            )
        assertThat(batch.rejected.single().reason).contains("NAME")
        assertThat(batch.files.keys).containsExactly("DIW6363.00001")
    }

    @Test
    fun `an overdrawn account is left out of DIA`() {
        val batch = render(
            listOf(account("CZ0100000000000000000001", "-5", alice), account("CZ0100000000000000000002", "7", alice)),
            listOf(identity(alice, "8001010009")),
        )
        assertThat(lines(batch, "DIA")).hasSize(1).allSatisfy { assertThat(it).contains("CZ0100000000000000000002") }
        assertThat(batch.rejected).isEmpty()
    }

    @Test
    fun `formats amounts as 15 digits of hundredths`() {
        assertThat(GsftFields.amount(BigDecimal("0.005"))).isEqualTo("000000000000001")
        assertThat(GsftFields.amount(BigDecimal("2500000"))).isEqualTo("000000250000000")
        assertThatThrownBy {
            GsftFields.amount(BigDecimal("1E13"))
        }.isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `doubles an embedded quote`() {
        assertThat(GsftFields.csvField("A \"B\"")).isEqualTo("\"A \"\"B\"\"\"")
        assertThat(GsftFields.csvField("plain")).isEqualTo("plain")
    }

    private val carol = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun amountAt(line: String, fromEnd: Int) = BigDecimal(line.split(",").reversed()[fromEnd]).movePointLeft(2)

    private fun assertConserves(principal: String, holders: List<UUID>, currency: String, fx: FxRates) {
        val joint = account("CZ0100000000000000000009", principal, *holders.toTypedArray(), currency = currency)
        val ids = holders.mapIndexed { i, h -> identity(h, "800101000$i", "Person $i") }
        val assembly = InsuredClaims.assemble(day, holders.map { Depositor(it, null, false) }, listOf(joint), fx)
        val batch = GsftBatchRenderer.render(assembly, listOf(joint), ids, fx, header)
        val dia = lines(batch, "DIA")
        val dil = lines(batch, "DIL")
        val diaCzk = amountAt(dia.first(), 0)
        val diaOrig = amountAt(dia.first(), 1)
        assertThat(dil).hasSize(holders.size)
        assertThat(dil.map { amountAt(it, 0) }.reduce(BigDecimal::add)).isEqualByComparingTo(diaCzk)
        assertThat(dil.map { amountAt(it, 1) }.reduce(BigDecimal::add)).isEqualByComparingTo(diaOrig)
        val dicClaims = lines(batch, "DIC").map { amountAt(it, 6) }
        assertThat(dicClaims.reduce(BigDecimal::add)).isEqualByComparingTo(diaCzk)
        assertThat(assembly.claims.map { it.eligibleBalanceCzk }.reduce(BigDecimal::add)).isEqualByComparingTo(diaCzk)
        assertThat(batch.totalPaymentCzk).isEqualByComparingTo(diaCzk)
    }

    @Test
    fun `joint shares of 1000_01 between two holders sum exactly to the total`() =
        assertConserves("1000.01", listOf(alice, bob), "CZK", rates)

    @Test
    fun `joint shares of 1000_00 between three holders sum exactly to the total`() =
        assertConserves("1000.00", listOf(alice, bob, carol), "CZK", rates)

    @Test
    fun `joint shares after FX conversion sum exactly to the converted total`() = assertConserves(
        "333.34",
        listOf(alice, bob, carol),
        "EUR",
        FxRates(day, mapOf("EUR" to BigDecimal("25.017"))),
    )

    @Test
    fun `a control character in a text field rejects that person only`() {
        for (bad in listOf("Jana\r\nNováková", "Jana\tNováková", "Jana\u0001")) {
            val batch = render(
                listOf(account("CZ0100000000000000000001", "1", alice), account("CZ0100000000000000000002", "2", bob)),
                listOf(identity(alice, "8001010009", bad), identity(bob, "8501010006", "Petr Novák")),
            )
            assertThat(batch.rejected.map { it.partyId }).containsExactly(alice)
            assertThat(batch.rejected.single().reason).contains("control character")
            assertThat(lines(batch, "DIC")).hasSize(1)
        }
    }

    @Test
    fun `a blank IDC or NAME rejects that person`() {
        val accounts = listOf(account("CZ0100000000000000000001", "1", alice))
        assertThat(render(accounts, listOf(identity(alice, "  "))).rejected.single().reason).contains("IDC")
        assertThat(render(accounts, listOf(identity(alice, "8001010009", " "))).rejected.single().reason)
            .contains("NAME")
    }

    @Test
    fun `a duplicated identity or account fails the whole batch`() {
        val acc = account("CZ0100000000000000000001", "1", alice)
        val assembly = InsuredClaims.assemble(day, listOf(Depositor(alice, null, false)), listOf(acc), rates)
        val id = identity(alice, "8001010009")
        assertThatThrownBy { GsftBatchRenderer.render(assembly, listOf(acc), listOf(id, id), rates, header) }
            .isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy { GsftBatchRenderer.render(assembly, listOf(acc, acc), listOf(id), rates, header) }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `carries exclusion and cap counts into the batch`() {
        val big = account("CZ0100000000000000000001", "3000000", alice)
        val synth =
            DepositAccount(
                UUID.randomUUID(),
                "CZ02",
                "CZK",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                listOf(Holding(alice, null)),
                true,
            )
        val people = listOf(Depositor(alice, null, false), Depositor(bob, ExclusionReason.CREDIT_INSTITUTION, false))
        val assembly = InsuredClaims.assemble(day, people, listOf(big, synth), rates)
        val batch = GsftBatchRenderer.render(
            assembly,
            listOf(big, synth),
            listOf(identity(alice, "8001010009")),
            rates,
            header,
        )
        assertThat(batch.cappedClaims).isEqualTo(1)
        assertThat(batch.droppedSyntheticAccounts).isEqualTo(1)
        assertThat(batch.excludedDepositors).containsEntry(ExclusionReason.CREDIT_INSTITUTION, 1)
    }

    @Test
    fun `encodes Czech letters as CP-1250 bytes`() {
        val batch = render(
            listOf(account("CZ0100000000000000000001", "1", alice)),
            listOf(identity(alice, "8001010009", "Šťastný Zdeněk ň")),
        )
        val bytes = batch.files.getValue("DIC6363.00001")
        val name = "E,8001010009,F,00001,1,".toByteArray(Charsets.US_ASCII).size
        assertThat(bytes[name]).isEqualTo(0x8A.toByte())
        assertThat(bytes.indexOf(0xF2.toByte())).isGreaterThan(name)
        assertThat(
            bytes.toList().subList(name, name + 3),
        ).containsExactly(0x8A.toByte(), 0x9D.toByte(), 'a'.code.toByte())
    }
}
