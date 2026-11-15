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

/** DII — special-regime accounts (decree annex 1.2.5) and the owner's uncapped payout (1.2.2). */
class GsftSpecialRegimeTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val rates = FxRates(day, mapOf("EUR" to BigDecimal("25.000")))
    private val cp1250 = Charset.forName("windows-1250")
    private val header = BatchHeader("6363", 1, LocalDateTime.of(2026, 11, 10, 8, 30, 0), BatchMode.TEST)

    private val firm = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val firm2 = UUID.fromString("00000000-0000-0000-0000-0000000000f2")
    private val ben = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    private fun identity(id: UUID, idc: String, type: EntitledPersonType = EntitledPersonType.LEGAL_PERSON) =
        DepositorIdentity(id, idc, type, "Name $idc", "Ulice 1", "Praha", "11000", "CZ", "cs")

    private fun special(iban: String, principal: String, owner: UUID, currency: String = "CZK") = DepositAccount(
        UUID.nameUUIDFromBytes(iban.toByteArray()),
        iban,
        currency,
        BigDecimal(principal),
        BigDecimal.ZERO,
        listOf(Holding(owner, null)),
        synthetic = false,
        specialRegime = true,
    )

    private fun lines(batch: GsftBatch, prefix: String) =
        String(batch.files.getValue("${prefix}6363.00001"), cp1250).split("\r\n").filter { it.isNotEmpty() }

    private fun render(
        accounts: List<DepositAccount>,
        owners: List<UUID>,
        shares: List<SpecialRegimeShare>,
    ): GsftBatch {
        val assembly = InsuredClaims.assemble(day, owners.map { Depositor(it, null, false) }, accounts, rates)
        val ids = owners.mapIndexed { i, o -> identity(o, "0000000$i") }
        return GsftBatchRenderer.render(assembly, accounts, ids, rates, header, shares)
    }

    @Test
    fun `the owner of a special-regime account is paid above the cover limit`() {
        // 5 000 000 CZK special balance; the CZK cover limit on 25.000 is 2 500 000.
        val account = special("CZ0100000000000000000009", "5000000", firm)
        val assembly = InsuredClaims.assemble(day, listOf(Depositor(firm, null, false)), listOf(account), rates)
        assertThat(assembly.claims.single().compensationCzk).isEqualByComparingTo("5000000.00")
        assertThat(assembly.cappedClaims).isZero()
    }

    @Test
    fun `ordinary deposits of the same owner stay capped`() {
        val ordinary = DepositAccount(
            UUID.randomUUID(),
            "CZ0100000000000000000001",
            "CZK",
            BigDecimal("3000000"),
            BigDecimal.ZERO,
            listOf(Holding(firm, null)),
            false,
        )
        val account = special("CZ0100000000000000000009", "100", firm)
        val claim = InsuredClaims.assemble(day, listOf(Depositor(firm, null, false)), listOf(ordinary, account), rates)
            .claims.single()
        assertThat(claim.eligibleBalanceCzk).isEqualByComparingTo("3000100.00")
        assertThat(claim.compensationCzk).isEqualByComparingTo("2500100.00")
    }

    @Test
    fun `renders DII rows, N_SPECIAL and a zero-payment DIC record for the beneficiary`() {
        val account = special("CZ0100000000000000000009", "1000", firm, currency = "EUR")
        val share =
            SpecialRegimeShare(
                identity(ben, "8001010009", EntitledPersonType.NATURAL_PERSON),
                account.accountId,
                BigDecimal("400.10"),
            )
        val batch = render(listOf(account), listOf(firm), listOf(share))

        assertThat(batch.files.keys).containsExactly("DIW6363.00001", "DIC6363.00001", "DIA6363.00001", "DII6363.00001")
        assertThat(lines(batch, "DII").single())
            .isEqualTo("D,8001010009,F,00001,CZ0100000000000000000009,EUR,000000000040010,000000001000250")
        // Owner 25 000 CZK uncapped; the beneficiary is paid 0 (paid through the owner).
        assertThat(lines(batch, "DIW").single()).isEqualTo(
            "R,20261110083000,00001,000000002,000000001,000000000,000000001,000000000,000000000,000000002500000,1,TEST",
        )
        val dic = lines(batch, "DIC")
        assertThat(dic.map { it.split(",")[1] }).containsExactly("00000000", "8001010009")
        assertThat(dic[1]).contains(",000000001000250,000000000000000,")
        // The beneficiary is never in DIA (1.2.5).
        assertThat(lines(batch, "DIA").single()).startsWith("T,00000000,P,")
    }

    @Test
    fun `no special shares means no DII file and N_SPECIAL zero`() {
        val batch = render(listOf(special("CZ0100000000000000000009", "10", firm)), listOf(firm), emptyList())
        assertThat(batch.files.keys).doesNotContain("DII6363.00001")
        assertThat(lines(batch, "DIW").single().split(",")[6]).isEqualTo("000000000")
    }

    @Test
    fun `a share on an ordinary account fails the batch`() {
        val ordinary = DepositAccount(
            UUID.randomUUID(),
            "CZ0100000000000000000001",
            "CZK",
            BigDecimal("10"),
            BigDecimal.ZERO,
            listOf(Holding(firm, null)),
            false,
        )
        val share = SpecialRegimeShare(identity(ben, "8001010009"), ordinary.accountId, BigDecimal("1"))
        assertThatThrownBy { render(listOf(ordinary), listOf(firm), listOf(share)) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("not a special-regime account")
    }

    @Test
    fun `shares above the account balance fail the batch`() {
        val account = special("CZ0100000000000000000009", "10", firm)
        val share = SpecialRegimeShare(identity(ben, "8001010009"), account.accountId, BigDecimal("10.01"))
        assertThatThrownBy { render(listOf(account), listOf(firm), listOf(share)) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("exceed")
    }

    @Test
    fun `a beneficiary through two owners fails because coefficient k is not computed`() {
        val a = special("CZ0100000000000000000009", "10", firm)
        val b = special("CZ0100000000000000000008", "10", firm2)
        val id = identity(ben, "8001010009")
        val shares =
            listOf(
                SpecialRegimeShare(id, a.accountId, BigDecimal("1")),
                SpecialRegimeShare(id, b.accountId, BigDecimal("1")),
            )
        assertThatThrownBy { render(listOf(a, b), listOf(firm, firm2), shares) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("coefficient k")
    }

    @Test
    fun `a beneficiary who is also a depositor fails the batch`() {
        val account = special("CZ0100000000000000000009", "10", firm)
        val share = SpecialRegimeShare(identity(firm, "00000000"), account.accountId, BigDecimal("1"))
        assertThatThrownBy { render(listOf(account), listOf(firm), listOf(share)) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("also a depositor")
    }

    @Test
    fun `a share whose owner was rejected fails the batch instead of leaving DII without DIA`() {
        val account = special("CZ0100000000000000000009", "10", firm)
        val assembly = InsuredClaims.assemble(day, listOf(Depositor(firm, null, false)), listOf(account), rates)
        val badOwner = identity(firm, "00000000").copy(name = " ")
        val share = SpecialRegimeShare(identity(ben, "8001010009"), account.accountId, BigDecimal("1"))
        assertThatThrownBy {
            GsftBatchRenderer.render(assembly, listOf(account), listOf(badOwner), rates, header, listOf(share))
        }.isInstanceOf(TaxValidationException::class.java).hasMessageContaining("not in DIA")
    }

    @Test
    fun `DII CZK shares reconcile with the owner's DIA CZK total`() {
        val odd = FxRates(day, mapOf("EUR" to BigDecimal("25.013")))
        val account = special("CZ0100000000000000000009", "10.00", firm, currency = "EUR")
        val shares = listOf("3.33", "3.33", "3.34").mapIndexed { i, a ->
            val id = UUID(0L, 100L + i)
            SpecialRegimeShare(
                identity(id, "900000000$i", EntitledPersonType.NATURAL_PERSON),
                account.accountId,
                BigDecimal(a),
            )
        }
        val assembly = InsuredClaims.assemble(day, listOf(Depositor(firm, null, false)), listOf(account), odd)
        val batch = GsftBatchRenderer.render(
            assembly,
            listOf(account),
            listOf(identity(firm, "00000000")),
            odd,
            header,
            shares,
        )
        val dia = lines(batch, "DIA").single().split(",").last()
        val dii = lines(batch, "DII").map { it.split(",").last().toLong() }
        assertThat(dia).isEqualTo("000000000025013")
        assertThat(dii.sum()).isEqualTo(25013L)
    }

    @Test
    fun `a share finer than hundredths fails the batch`() {
        val account = special("CZ0100000000000000000009", "10", firm)
        val share = SpecialRegimeShare(identity(ben, "8001010009"), account.accountId, BigDecimal("4.505"))
        assertThatThrownBy { render(listOf(account), listOf(firm), listOf(share)) }
            .isInstanceOf(TaxValidationException::class.java).hasMessageContaining("hundredths")
    }

    @Test
    fun `a beneficiary with a blank name is rejected alone`() {
        val account = special("CZ0100000000000000000009", "10", firm)
        val good = SpecialRegimeShare(identity(ben, "8001010009"), account.accountId, BigDecimal("1"))
        val bad =
            SpecialRegimeShare(
                identity(UUID(0L, 7L), "8001010010").copy(name = " "),
                account.accountId,
                BigDecimal("1"),
            )
        val batch = render(listOf(account), listOf(firm), listOf(good, bad))
        assertThat(batch.rejected.map { it.partyId }).containsExactly(UUID(0L, 7L))
        assertThat(lines(batch, "DII").map { it.split(",")[1] }).containsExactly("8001010009")
        assertThat(lines(batch, "DIW").single().split(",")[6]).isEqualTo("000000001")
    }
}
