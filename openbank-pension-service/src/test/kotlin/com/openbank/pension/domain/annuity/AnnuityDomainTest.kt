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
        assertThatThrownBy {
            pending.approveActivation("maker", now)
        }.isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `four-eyes - whoever edited the terms cannot approve them, even if someone else requested`() {
        val pending = AnnuityProvider.draft("alpha", terms(), "editor", now).requestActivation("requester", now)
        assertThatThrownBy {
            pending.approveActivation("editor", now)
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThat(pending.approveActivation("checker", now).status).isEqualTo(AnnuityProviderStatus.ACTIVE)
    }

    @Test
    fun `a pending edit is invisible - the approved version stays live until a checker approves the new one`() {
        val active = AnnuityProvider.draft("alpha", terms(), "maker", now)
            .requestActivation("maker", now).approveActivation("checker", now)
        val pending = active.propose(terms(premiumIban = "CZ1208000000009876543210"), "maker", now)
            .requestActivation("maker", now)
        val live = requireNotNull(pending.live(today))
        assertThat(live.versionNo).isEqualTo(1)
        assertThat(live.terms.premiumIban).isEqualTo("CZ6508000000192000145399")
        val approved = pending.approveActivation("checker", now)
        assertThat(requireNotNull(approved.live(today)).versionNo).isEqualTo(2)
        assertThat(requireNotNull(approved.live(today)).terms.premiumIban).isEqualTo("CZ1208000000009876543210")
    }

    @Test
    fun `four-eyes - the maker cannot approve their own change to an active partner`() {
        val active = AnnuityProvider.draft("alpha", terms(), "maker", now)
            .requestActivation("maker", now).approveActivation("checker", now)
        val own = active.propose(
            terms(maxPremium = BigDecimal("9000000")),
            "checker",
            now,
        ).requestActivation("other", now)
        assertThatThrownBy {
            own.approveActivation("checker", now)
        }.isInstanceOf(FourEyesViolationException::class.java)
        assertThat(own.live(today)?.versionNo).isEqualTo(1)
    }

    @Test
    fun `an unapproved draft is never live`() {
        val draft = AnnuityProvider.draft("alpha", terms(), "maker", now).requestActivation("maker", now)
        assertThat(draft.live(today)).isNull()
        assertThat(draft.eligible("CZ", "CZK", BigDecimal("100000"), today)).isFalse()
    }

    @Test
    fun `terms refuse a malformed registry entry`() {
        assertThatThrownBy { terms(premiumIban = "not-an-iban") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { terms(adapter = "Reference REST") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            AnnuityProvider.draft("Bad Id", terms(), "m", now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the selection hash binds partner, offer id and amounts`() {
        val purchase = purchase()
        val a = offer("alpha", "o1", "4000")
        val base = purchase.selectionHash(a)
        assertThat(purchase.selectionHash(a.copy(partnerId = "beta"))).isNotEqualTo(base)
        assertThat(purchase.selectionHash(a.copy(offerId = "o2"))).isNotEqualTo(base)
        assertThat(purchase.selectionHash(a.copy(monthlyAmount = BigDecimal("4000.01")))).isNotEqualTo(base)
        assertThat(purchase.selectionHash(a.copy(providerVersion = 2))).isNotEqualTo(base)
        assertThat(purchase.copy(premium = BigDecimal("1000000.01")).selectionHash(a)).isNotEqualTo(base)
    }

    @Test
    fun `only a presented, unexpired offer can be selected, and the binding selection re-checks premium and expiry`() {
        val purchase = purchase()
        assertThatThrownBy {
            purchase.select("alpha", "nope", "sca", now)
        }.isInstanceOf(IllegalArgumentException::class.java)
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
        val applied = purchase().select("alpha", "o1", "sca", now).markApplied("APP-1", partner(), now)
        assertThatThrownBy { applied.markActive("POL", BigDecimal.TEN, today, 30, now) }
            .isInstanceOf(IllegalStateException::class.java)
        val active = applied.markPremiumSent("PAY-1", now).markActive("POL", BigDecimal.TEN, today, 30, now)
        assertThat(active.coolingOffEndsOn).isEqualTo(today.plusDays(30))
    }

    @Test
    fun `cancellation is possible only within the cooling-off period`() {
        val active = purchase().select(
            "alpha",
            "o1",
            "sca",
            now,
        ).markApplied("A", partner(), now).markPremiumSent("P", now)
            .markActive("POL", BigDecimal.TEN, today, 30, now)
        assertThatThrownBy {
            active.cancelInCoolingOff(today.plusDays(31), now)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(active.cancelInCoolingOff(today.plusDays(30), now).status).isEqualTo(AnnuityPurchaseStatus.CANCELLED)
    }

    @Test
    fun `a partner offer that breaks the normalised shape is refused`() {
        assertThatThrownBy { offer("alpha", "o1", "0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { offer("alpha", "o1", "10").copy(type = AnnuityType.FIXED_TERM) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun partner() = ApprovedPartner("alpha", 0, terms())

    @Test
    fun `an application is recorded only under the very version the offer was quoted under`() {
        val selected = purchase().select("alpha", "o1", "sca", now)
        assertThatThrownBy { selected.markApplied("A", ApprovedPartner("alpha", 2, terms()), now) }
            .isInstanceOf(IllegalStateException::class.java)
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
