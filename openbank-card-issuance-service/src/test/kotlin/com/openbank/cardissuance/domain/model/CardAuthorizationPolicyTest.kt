// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CardAuthorizationPolicyTest {
    private fun card(
        status: CardStatus = CardStatus.ACTIVE,
        contactless: Boolean = true,
        online: Boolean = true,
        atm: Boolean = true,
        abroad: Boolean = true,
        daily: Long = 500_000,
        monthly: Long = 5_000_000,
    ) = Card(
        id = UUID.randomUUID(),
        idempotencyKey = "k",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productCode = "P",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        maskedPan = "**** 1234",
        cardholderName = "J R",
        embossedName = "J R",
        expiryDate = LocalDate.of(2030, 1, 1),
        status = status,
        dailyLimitMinorUnits = daily,
        monthlyLimitMinorUnits = monthly,
        currency = "CZK",
        deliveryAddress = null,
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        contactlessEnabled = contactless,
        onlineEnabled = online,
        atmEnabled = atm,
        abroadEnabled = abroad,
    )

    private fun req(
        amount: Long = 10_000,
        channel: AuthorizationChannel = AuthorizationChannel.CHIP_AND_PIN,
        mcc: String? = "5411",
        country: String? = "CZ",
        today: Long = 0,
        month: Long = 0,
        monthCategory: Long = 0,
    ) = AuthorizationRequest(amount, channel, mcc, country, today, month, monthCategory)

    @Test
    fun `an ordinary purchase on an active card is approved`() {
        val d = CardAuthorizationPolicy.decide(card(), req(), emptyList())
        assertThat(d.approved).isTrue()
        assertThat(d.reason).isNull()
        assertThat(d.category).isEqualTo("GROCERIES")
    }

    @Test
    fun `a card that is not active never authorises`() {
        for (s in CardStatus.entries.filter { it != CardStatus.ACTIVE }) {
            val d = CardAuthorizationPolicy.decide(card(status = s), req(), emptyList())
            assertThat(d.approved).describedAs("status $s").isFalse()
            assertThat(d.reason).isEqualTo(DeclineReason.CARD_NOT_ACTIVE)
        }
    }

    @Test
    fun `each channel toggle declines only its own channel`() {
        val contactless = req(channel = AuthorizationChannel.CONTACTLESS)
        assertThat(CardAuthorizationPolicy.decide(card(contactless = false), contactless, emptyList()).reason)
            .isEqualTo(DeclineReason.CHANNEL_DISABLED)
        assertThat(CardAuthorizationPolicy.decide(card(online = false), contactless, emptyList()).approved).isTrue()

        val online = req(channel = AuthorizationChannel.ONLINE)
        assertThat(CardAuthorizationPolicy.decide(card(online = false), online, emptyList()).reason)
            .isEqualTo(DeclineReason.CHANNEL_DISABLED)

        val atm = req(channel = AuthorizationChannel.ATM)
        assertThat(CardAuthorizationPolicy.decide(card(atm = false), atm, emptyList()).reason)
            .isEqualTo(DeclineReason.CHANNEL_DISABLED)
    }

    @Test
    fun `chip and pin has no toggle so it cannot be switched off`() {
        // Deliberate: a customer who could disable every rail would lock themselves out with no
        // way back in from a terminal.
        val d = CardAuthorizationPolicy.decide(
            card(contactless = false, online = false, atm = false),
            req(channel = AuthorizationChannel.CHIP_AND_PIN),
            emptyList(),
        )
        assertThat(d.approved).isTrue()
    }

    @Test
    fun `abroad is judged against the issuing market`() {
        assertThat(CardAuthorizationPolicy.decide(card(abroad = false), req(country = "DE"), emptyList()).reason)
            .isEqualTo(DeclineReason.ABROAD_DISABLED)
        assertThat(CardAuthorizationPolicy.decide(card(abroad = false), req(country = "CZ"), emptyList()).approved)
            .isTrue()
        // Case is the acquirer's business, not the customer's.
        assertThat(CardAuthorizationPolicy.decide(card(abroad = false), req(country = "cz"), emptyList()).approved)
            .isTrue()
    }

    @Test
    fun `a missing country counts as domestic`() {
        // Declining on absent data would break ordinary spend at terminals with sparse messages,
        // to enforce a control the customer may never have set.
        val d = CardAuthorizationPolicy.decide(card(abroad = false), req(country = null), emptyList())
        assertThat(d.approved).isTrue()
    }

    @Test
    fun `a blocked category declines`() {
        val rules = listOf(CategoryRule("GAMBLING", blocked = true))
        val d = CardAuthorizationPolicy.decide(card(), req(mcc = "7995"), rules)
        assertThat(d.reason).isEqualTo(DeclineReason.CATEGORY_BLOCKED)
        assertThat(d.category).isEqualTo("GAMBLING")
    }

    @Test
    fun `blocking one category leaves the others alone`() {
        val rules = listOf(CategoryRule("GAMBLING", blocked = true))
        assertThat(CardAuthorizationPolicy.decide(card(), req(mcc = "5411"), rules).approved).isTrue()
    }

    @Test
    fun `the unmapped category cannot be blocked`() {
        // Blocking "everything I have not classified" would decline arbitrary legitimate spend as
        // the acquirer estate shifts, and the customer could never see what they had turned off.
        val rules = listOf(CategoryRule(MerchantCategoryTaxonomy.UNMAPPED, blocked = true))
        val d = CardAuthorizationPolicy.decide(card(), req(mcc = "9999"), rules)
        assertThat(d.category).isEqualTo(MerchantCategoryTaxonomy.UNMAPPED)
        assertThat(d.approved).isTrue()
    }

    @Test
    fun `a category limit counts the spend already made in that category`() {
        val rules = listOf(CategoryRule("GROCERIES", monthlyLimitMinorUnits = 100_000))
        // Exactly at the limit is allowed; one minor unit past it is not.
        assertThat(
            CardAuthorizationPolicy.decide(card(), req(amount = 40_000, monthCategory = 60_000), rules).approved,
        ).isTrue()
        assertThat(
            CardAuthorizationPolicy.decide(card(), req(amount = 40_001, monthCategory = 60_000), rules).reason,
        ).isEqualTo(DeclineReason.CATEGORY_LIMIT_EXCEEDED)
    }

    @Test
    fun `a category limit on another category does not apply`() {
        val rules = listOf(CategoryRule("GAMBLING", monthlyLimitMinorUnits = 1))
        assertThat(CardAuthorizationPolicy.decide(card(), req(mcc = "5411", amount = 400_000), rules).approved)
            .isTrue()
    }

    @Test
    fun `daily and monthly card limits still apply`() {
        assertThat(CardAuthorizationPolicy.decide(card(daily = 50_000), req(amount = 50_001), emptyList()).reason)
            .isEqualTo(DeclineReason.DAILY_LIMIT_EXCEEDED)
        assertThat(
            CardAuthorizationPolicy.decide(
                card(daily = 1_000_000, monthly = 100_000),
                req(amount = 100_001),
                emptyList(),
            ).reason,
        ).isEqualTo(DeclineReason.MONTHLY_LIMIT_EXCEEDED)
    }

    @Test
    fun `the reason shown is the one the customer can act on`() {
        // A blocked category on an over-limit request reports the block: the customer switched that
        // on themselves and can switch it off. Reporting the limit would send them to the wrong dial.
        val rules = listOf(CategoryRule("GAMBLING", blocked = true))
        val d = CardAuthorizationPolicy.decide(
            card(daily = 1),
            req(mcc = "7995", amount = 999_999),
            rules,
        )
        assertThat(d.reason).isEqualTo(DeclineReason.CATEGORY_BLOCKED)
    }

    @Test
    fun `card state outranks everything the customer configured`() {
        val rules = listOf(CategoryRule("GAMBLING", blocked = true))
        val d = CardAuthorizationPolicy.decide(
            card(status = CardStatus.BLOCKED),
            req(mcc = "7995"),
            rules,
        )
        assertThat(d.reason).isEqualTo(DeclineReason.CARD_NOT_ACTIVE)
    }

    @Test
    fun `a zero amount is judged like any other`() {
        assertThat(CardAuthorizationPolicy.decide(card(), req(amount = 0), emptyList()).approved).isTrue()
    }
}
