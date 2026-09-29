// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.reserves

import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.infrastructure.MinReservesConfig
import com.openbank.risk.infrastructure.toParameters
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Opatření ČNB o povinných minimálních rezervách, one rule per test, against the parameter set
 * exactly as application.yaml ships it (so a changed rate in configuration reddens these tests).
 */
class MinimumReservesTest {

    private val params: MinReserveParameters = shipped()

    private fun gl(code: String, type: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun customer(amount: String, ccy: String = "CZK") =
        Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", ccy, UUID.randomUUID(), BigDecimal(amount))

    @Test
    fun `shipped set cnb-pmr v2 - rate 2 percent (Opatreni CNB o PMR), 0 remuneration since 2023-10-05, CZK`() {
        assertThat(params.id).isEqualTo("cnb-pmr")
        assertThat(params.version).isEqualTo("2")
        assertThat(params.rate).isEqualByComparingTo("0.02")
        assertThat(params.remunerationRate).isEqualByComparingTo("0")
        assertThat(params.holdingCurrency).isEqualTo("CZK")
    }

    @Test
    fun `customer deposits - liabilities to non-bank clients - form the base and the requirement is 2 percent of it`() {
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), customer("-500.00"), customer("200.00")), params)
        val czk = r.currencies.single()
        // the overdraft (debit, an asset) never enters the base
        assertThat(czk.base).isEqualByComparingTo("1500.00")
        assertThat(czk.requirement).isEqualByComparingTo("30.00")
        assertThat(r.requirement).isEqualByComparingTo("30.00")
        assertThat(czk.lines.single().label).isEqualTo("Customer deposits (2 accounts)")
    }

    @Test
    fun `liabilities to banks subject to PMR are excluded from the base`() {
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), gl("2300", "LIABILITY", "-5000.00")), params)
        assertThat(r.currencies.single().base).isEqualByComparingTo("1000.00")
        assertThat(r.excluded.map { it.glAccountCode to it.reserveClass })
            .containsExactly("2300" to ReserveClass.EXCLUDED_BANK_OR_CNB)
    }

    @Test
    fun `no CNB current account mapped - holdings and surplus not stated, 1510 facility is no holding`() {
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), gl("1510", "ASSET", "4000.00")), params)
        assertThat(r.requirement).isEqualByComparingTo("20.00")
        assertThat(r.holdings).isNull()
        assertThat(r.totalHoldings).isNull()
        assertThat(r.surplus).isNull()
        assertThat(r.holdingsNotStated).isEqualTo(MinimumReserves.HOLDINGS_NOT_STATED)
        assertThat(r.excluded.single().reserveClass).isEqualTo(ReserveClass.NOT_A_HOLDING)
    }

    @Test
    fun `with a CNB current account mapped, its balance is holdings and gives the surplus`() {
        val withAccount = params.copy(glAccounts = params.glAccounts + ("1599" to ReserveClass.RESERVE_HOLDING))
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), gl("1599", "ASSET", "50.00")), withAccount)
        assertThat(r.holdingsNotStated).isNull()
        assertThat(r.totalHoldings).isEqualByComparingTo("50.00")
        assertThat(r.surplus).isEqualByComparingTo("30.00")
    }

    @Test
    fun `with a CNB current account mapped but no balance, a zero holding is stated and the shortfall is real`() {
        val withAccount = params.copy(glAccounts = params.glAccounts + ("1599" to ReserveClass.RESERVE_HOLDING))
        val r = MinimumReserves.compute(listOf(customer("-1000.00")), withAccount)
        assertThat(r.totalHoldings).isEqualByComparingTo("0")
        assertThat(r.surplus).isEqualByComparingTo("-20.00")
    }

    @Test
    fun `required reserves earn nothing - remuneration 0 since 2023-10-05`() {
        val r = MinimumReserves.compute(listOf(customer("-1000000.00")), params)
        assertThat(r.remuneration).isEqualByComparingTo("0")
    }

    @Test
    fun `an unmapped liability is listed as unclassified and leaves base and requirement not stated`() {
        val r = MinimumReserves.compute(listOf(customer("-100.00"), gl("2200", "LIABILITY", "-70.00")), params)
        val czk = r.currencies.single()
        // 100.00 would be a partial sum: the 70.00 may belong in the base, so no base is stated at all
        assertThat(czk.base).isNull()
        assertThat(czk.requirement).isNull()
        assertThat(czk.requirementNotStated).isEqualTo(MinimumReserves.UNCLASSIFIED_LIABILITY)
        assertThat(r.requirement).isNull()
        assertThat(r.surplus).isNull()
        assertThat(r.unclassified.map { it.glAccountCode }).containsExactly("2200")
    }

    @Test
    fun `an unclassified liability in one currency does not unstate another currency's requirement`() {
        val r = MinimumReserves.compute(
            listOf(customer("-1000.00"), customer("-10.00", "EUR"), gl("2200", "LIABILITY", "-7.00", "EUR")),
            params,
        )
        assertThat(r.currencies.single { it.currency == "EUR" }.requirement).isNull()
        assertThat(r.currencies.single { it.currency == "CZK" }.requirement).isEqualByComparingTo("20.00")
        assertThat(r.requirement).isEqualByComparingTo("20.00")
    }

    @Test
    fun `an unclassified ASSET does not unstate the requirement`() {
        val noAssetType = params.copy(glAccountTypes = params.glAccountTypes - "ASSET")
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), gl("1999", "ASSET", "5.00")), noAssetType)
        assertThat(r.unclassified.map { it.glAccountCode }).containsExactly("1999")
        assertThat(r.requirement).isEqualByComparingTo("20.00")
    }

    @Test
    fun `a treasury deal on 2301 (EUR borrowing from a bank) is excluded like its GL account, not dropped`() {
        val deal = Position(PositionKind.TREASURY_DEAL, "2301", "LIABILITY", "EUR", null, BigDecimal("-800.00"))
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), deal), params)
        assertThat(r.excluded.map { Triple(it.glAccountCode, it.amount.toPlainString(), it.reserveClass) })
            .containsExactly(Triple("2301", "-800.00", ReserveClass.EXCLUDED_BANK_OR_CNB))
        assertThat(r.unclassified).isEmpty()
        assertThat(r.currencies.single { it.currency == "EUR" }.base).isEqualByComparingTo("0")
        assertThat(r.currencies.single { it.currency == "CZK" }.base).isEqualByComparingTo("1000.00")
    }

    @Test
    fun `CNB lombard borrowing 2320 is a liability to the CNB and excluded from the base`() {
        assertThat(params.classOf("2320", "LIABILITY")).isEqualTo(ReserveClass.EXCLUDED_BANK_OR_CNB)
    }

    @Test
    fun `equity, income and expense are not liabilities and never in the base`() {
        val r = MinimumReserves.compute(
            listOf(gl("6000", "EQUITY", "-10.00"), gl("4100", "INCOME", "-5.00"), gl("5100", "EXPENSE", "5.00")),
            params,
        )
        assertThat(r.currencies.single().base).isEqualByComparingTo("0")
        assertThat(r.unclassified).isEmpty()
        assertThat(r.excluded).hasSize(3)
    }

    @Test
    fun `a multi-currency book reports the base per currency, the CZK requirement, and no requirement vs holdings`() {
        val r = MinimumReserves.compute(listOf(customer("-1000.00"), customer("-10.00", "EUR")), params)
        assertThat(r.currencies.map { it.currency to it.base?.toPlainString() })
            .containsExactly("CZK" to "1000.00", "EUR" to "10.00")
        // the CZK book is fully classified, so its requirement stands; an EUR asset or liability
        // does not unstate it — only the comparison with holdings needs a single-currency book
        assertThat(r.requirement).isEqualByComparingTo("20.00")
        assertThat(r.surplus).isNull()
        assertThat(r.notes).contains(MinimumReserves.CURRENCY_NOTE)
    }

    @Test
    fun `with holdings stated, a multi-currency book still gets no surplus (no FX conversion)`() {
        val withAccount = params.copy(glAccounts = params.glAccounts + ("1599" to ReserveClass.RESERVE_HOLDING))
        val r = MinimumReserves.compute(
            listOf(customer("-1000.00"), customer("-10.00", "EUR"), gl("1599", "ASSET", "50.00")),
            withAccount,
        )
        assertThat(r.requirement).isEqualByComparingTo("20.00")
        assertThat(r.totalHoldings).isEqualByComparingTo("50.00")
        assertThat(r.surplus).isNull()
    }

    @Test
    fun `every result says the surplus is one day while the requirement is averaged over the maintenance period`() {
        assertThat(MinimumReserves.compute(emptyList(), params).notes).contains(MinimumReserves.AVERAGING_NOTE)
    }

    @Test
    fun `a rate outside 0 to 1 is refused`() {
        assertThatThrownBy { params.copy(rate = BigDecimal("2")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun shipped(): MinReserveParameters {
        val url = requireNotNull(javaClass.classLoader.getResource("application.yaml"))
        val config = SmallRyeConfigBuilder().withSources(YamlConfigSource(url))
            .withMapping(MinReservesConfig::class.java).build()
        return config.getConfigMapping(MinReservesConfig::class.java).toParameters()
    }
}
