// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.annuity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AnnuityDomainTest {

    private val now = Instant.parse("2030-01-01T10:00:00Z")
    private val today = LocalDate.parse("2030-01-01")

    @Test
    fun `four-eyes - the requester cannot approve their own activation`() {
        val pending = AnnuityProvider.draft("alpha", terms(), "maker", now).requestActivation("maker", now)
        assertThatThrownBy { pending.approveActivation("maker", now) }.isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `four-eyes - whoever edited the terms cannot approve them, even if someone else requested`() {
        val pending = AnnuityProvider.draft("alpha", terms(), "editor", now).requestActivation("requester", now)
        assertThatThrownBy { pending.approveActivation("editor", now) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThat(pending.approveActivation("checker", now).status).isEqualTo(AnnuityProviderStatus.ACTIVE)
    }

    @Test
    fun `amending an active partner sends it back to DRAFT - changed terms are never live unchecked`() {
        val active = AnnuityProvider.draft("alpha", terms(), "maker", now)
            .requestActivation("maker", now).approveActivation("checker", now)
        val amended = active.amend(terms(maxPremium = BigDecimal("9000000")), "checker", now)
        assertThat(amended.status).isEqualTo(AnnuityProviderStatus.DRAFT)
        assertThat(amended.eligible("CZ", "CZK", BigDecimal("100000"), today)).isFalse()
        // The amender is now the editor and can no longer approve.
        assertThatThrownBy { amended.requestActivation("x", now).approveActivation("checker", now) }
            .isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `eligibility needs ACTIVE, jurisdiction, currency, premium band and effective dates`() {
        val active = AnnuityProvider.draft("alpha", terms(), "m", now).requestActivation("m", now).approveActivation("c", now)
        assertThat(active.eligible("CZ", "CZK", BigDecimal("100000"), today)).isTrue()
        assertThat(active.eligible("SK", "CZK", BigDecimal("100000"), today)).isFalse()
        assertThat(active.eligible("CZ", "EUR", BigDecimal("100000"), today)).isFalse()
        assertThat(active.eligible("CZ", "CZK", BigDecimal("9999"), today)).isFalse()
        assertThat(active.eligible("CZ", "CZK", BigDecimal("100000"), LocalDate.parse("2029-12-31"))).isFalse()
        assertThat(active.disable(now).eligible("CZ", "CZK", BigDecimal("100000"), today)).isFalse()
    }

    @Test
    fun `terms refuse a malformed registry entry`() {
        assertThatThrownBy { terms(premiumIban = "not-an-iban") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { terms(adapter = "Reference REST") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AnnuityProvider.draft("Bad Id", terms(), "m", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the selection hash binds partner, offer id and amounts`() {
        val purchase = purchase()
        val a = offer("alpha", "o1", "4000")
        val base = purchase.selectionHash(a)
        assertThat(purchase.selectionHash(a.copy(partnerId = "beta"))).isNotEqualTo(base)
        assertThat(purchase.selectionHash(a.copy(offerId = "o2"))).isNotEqualTo(base)
        assertThat(purchase.selectionHash(a.copy(monthlyAmount = BigDecimal("4000.01")))).isNotEqualTo(base)
        assertThat(purchase.copy(premium = BigDecimal("1000000.01")).selectionHash(a)).isNotEqualTo(base)
    }

    @Test
    fun `only a presented, unexpired offer can be selected, and the binding selection re-checks premium and expiry`() {
        val purchase = purchase()
        assertThatThrownBy { purchase.select("alpha", "nope", "sca", now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { purchase.select("alpha", "o1", "sca", now.plusSeconds(DAY * 3)) }
            .isInstanceOf(IllegalStateException::class.java)
        val selected = purchase.select("alpha", "o1", "sca", now)
        assertThat(selected.requireBindingSelection(BigDecimal("1000000.00"), now).offerId).isEqualTo("o1")
        assertThatThrownBy { selected.requireBindingSelection(BigDecimal("999999.00"), now) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { selected.requireBindingSelection(BigDecimal("1000000.00"), now.plusSeconds(DAY * 3)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { purchase.requireBindingSelection(BigDecimal("1000000.00"), now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `premium not sent - no policy`() {
        val applied = purchase().select("alpha", "o1", "sca", now).markApplied("APP-1", now)
        assertThatThrownBy { applied.markActive("POL", BigDecimal.TEN, today, 30, now) }
            .isInstanceOf(IllegalStateException::class.java)
        val active = applied.markPremiumSent("PAY-1", now).markActive("POL", BigDecimal.TEN, today, 30, now)
        assertThat(active.coolingOffEndsOn).isEqualTo(today.plusDays(30))
    }

    @Test
    fun `cancellation is possible only within the cooling-off period`() {
        val active = purchase().select("alpha", "o1", "sca", now).markApplied("A", now).markPremiumSent("P", now)
            .markActive("POL", BigDecimal.TEN, today, 30, now)
        assertThatThrownBy { active.cancelInCoolingOff(today.plusDays(31), now) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(active.cancelInCoolingOff(today.plusDays(30), now).status).isEqualTo(AnnuityPurchaseStatus.CANCELLED)
    }

    @Test
    fun `a partner offer that breaks the normalised shape is refused`() {
        assertThatThrownBy { offer("alpha", "o1", "0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { offer("alpha", "o1", "10").copy(type = AnnuityType.FIXED_TERM) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun purchase() = AnnuityPurchase.offered(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        UUID.randomUUID(),
        BigDecimal("1000000.00"),
        "CZK",
        listOf(offer("alpha", "o1", "4000"), offer("beta", "o9", "4100")),
        emptyList(),
        now,
    )

    private fun offer(partner: String, id: String, monthly: String) = AnnuityOffer(
        offerId = id,
        partnerId = partner,
        partnerName = partner,
        type = AnnuityType.LIFELONG,
        premium = BigDecimal("1000000.00"),
        currency = "CZK",
        monthlyAmount = BigDecimal(monthly),
        validUntil = now.plusSeconds(DAY * 2),
        illustrative = true,
    )

    private fun terms(
        maxPremium: BigDecimal = BigDecimal("5000000"),
        premiumIban: String = "CZ6508000000192000145399",
        adapter: String = "simulator",
    ) = AnnuityProviderTerms(
        legalName = "Alpha Life",
        legalEntityPartyId = UUID.randomUUID(),
        licenceRef = "CNB-1",
        licenceAuthority = "CNB",
        jurisdictions = setOf("CZ"),
        supportedTypes = setOf(AnnuityType.LIFELONG),
        currency = "CZK",
        minPremium = BigDecimal("10000"),
        maxPremium = maxPremium,
        coolingOffDays = 30,
        premiumIban = premiumIban,
        adapter = adapter,
        effectiveFrom = LocalDate.parse("2030-01-01"),
    )

    private companion object {
        const val DAY = 86_400L
    }
}
