// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class ConsentTest {

    private val partyId = UUID.randomUUID()
    private val baseValidFrom = OffsetDateTime.now().plusMinutes(1)

    @Test
    fun `isActive returns true for ACTIVE consent before validTo`() {
        val consent = consent(status = ConsentStatus.ACTIVE, validTo = baseValidFrom.plusDays(1))

        assertThat(consent.isActive(OffsetDateTime.now())).isTrue()
    }

    @Test
    fun `isActive returns false for REVOKED consent`() {
        val consent = consent(status = ConsentStatus.REVOKED, validTo = baseValidFrom.plusDays(1))

        assertThat(consent.isActive(OffsetDateTime.now())).isFalse()
    }

    @Test
    fun `hasScope checks membership`() {
        val consent = consent(scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.BALANCES_READ))

        assertThat(consent.hasScope(ConsentScope.ACCOUNTS_READ)).isTrue()
        assertThat(consent.hasScope(ConsentScope.TRANSACTIONS_READ)).isFalse()
    }

    @Test
    fun `coversAccount returns true when accountIbans is null`() {
        val consent = consent(accountIbans = null)

        assertThat(consent.coversAccount("CZ6508000000192000145399")).isTrue()
    }

    @Test
    fun `coversAccount returns false when IBAN not in list`() {
        val consent = consent(accountIbans = listOf("CZ6508000000192000145399"))

        assertThat(consent.coversAccount("CZ7108000000192000145399")).isFalse()
    }

    @Test
    fun `revoke sets status to REVOKED`() {
        val consent = consent()

        assertThat(consent.revoke("customer request", OffsetDateTime.now()).status).isEqualTo(ConsentStatus.REVOKED)
    }

    @Test
    fun `activate sets status to ACTIVE with scaSessionId`() {
        val scaSessionId = UUID.randomUUID()

        val activated = consent(status = ConsentStatus.PENDING_SCA).activate(scaSessionId, OffsetDateTime.now())

        assertThat(activated.status).isEqualTo(ConsentStatus.ACTIVE)
        assertThat(activated.scaSessionId).isEqualTo(scaSessionId)
    }

    @Test
    fun `reject sets status to REJECTED`() {
        val rejected = consent(status = ConsentStatus.PENDING_SCA).reject(OffsetDateTime.now())

        assertThat(rejected.status).isEqualTo(ConsentStatus.REJECTED)
    }

    @Test
    fun `init validation rejects empty scopes`() {
        assertThrows<IllegalArgumentException> {
            consent(scopes = emptySet())
        }
    }

    @Test
    fun `init validation rejects validTo before validFrom`() {
        assertThrows<IllegalArgumentException> {
            consent(validFrom = baseValidFrom, validTo = baseValidFrom.minusDays(1))
        }
    }

    @Test
    fun `init validation rejects AISP scopes beyond 90 days`() {
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.ACCOUNTS_READ),
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(91),
            )
        }
    }

    @Test
    fun `TELEMETRY_RUM consent is allowed past 90 days (GDPR, not AISP-capped)`() {
        val consent = consent(
            scopes = setOf(ConsentScope.TELEMETRY_RUM),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(consent.hasScope(ConsentScope.TELEMETRY_RUM)).isTrue()
        assertThat(consent.isActive(OffsetDateTime.now())).isTrue()
    }

    @Test
    fun `TELEMETRY_RUM consent is still capped at the 365-day maximum`() {
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.TELEMETRY_RUM),
                accountIbans = null,
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(366),
            )
        }
    }

    @Test
    fun `frequencyPerDay returns the PSD2 AISP cap for AISP scopes`() {
        val consent = consent(scopes = setOf(ConsentScope.ACCOUNTS_READ))

        assertThat(consent.frequencyPerDay()).isEqualTo(Consent.AISP_MAX_ACCESSES_PER_DAY)
    }

    @Test
    fun `frequencyPerDay is null for non-AISP scopes`() {
        val consent = consent(
            scopes = setOf(ConsentScope.TELEMETRY_RUM),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(consent.frequencyPerDay()).isNull()
    }

    // ADR-0198 D1: the three MARKETING_COMMS_* scopes must behave exactly like TELEMETRY_RUM —
    // GDPR Art. 7, not AISP, not SCA-gated, 365-day bucket. The Negative-consequences section of
    // ADR-0198 explicitly requires this exclusion from AISP_SCOPES be a reviewed, testable line,
    // not an assumption resting on exhaustiveness that does not exist in this codebase.
    @Test
    fun `MARKETING_COMMS scopes are allowed past 90 days (GDPR, not AISP-capped)`() {
        val emailConsent = consent(
            scopes = setOf(ConsentScope.MARKETING_COMMS_EMAIL),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )
        val pushConsent = consent(
            scopes = setOf(ConsentScope.MARKETING_COMMS_PUSH),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )
        val inAppConsent = consent(
            scopes = setOf(ConsentScope.MARKETING_COMMS_INAPP),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(emailConsent.hasScope(ConsentScope.MARKETING_COMMS_EMAIL)).isTrue()
        assertThat(pushConsent.hasScope(ConsentScope.MARKETING_COMMS_PUSH)).isTrue()
        assertThat(inAppConsent.hasScope(ConsentScope.MARKETING_COMMS_INAPP)).isTrue()
        assertThat(emailConsent.isActive(OffsetDateTime.now())).isTrue()
    }

    @Test
    fun `MARKETING_COMMS scopes are still capped at the 365-day maximum`() {
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.MARKETING_COMMS_EMAIL),
                accountIbans = null,
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(366),
            )
        }
    }

    @Test
    fun `frequencyPerDay is null for MARKETING_COMMS scopes — they are not AISP_SCOPES`() {
        val consent = consent(
            scopes = setOf(ConsentScope.MARKETING_COMMS_PUSH),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(consent.frequencyPerDay()).isNull()
    }

    @Test
    fun `a MARKETING_COMMS scope never triggers the PSD2 90-day AISP cap even mixed with an AISP scope`() {
        // If a consent ever carried both an AISP scope and a marketing scope, the stricter (AISP)
        // cap must still apply — this is the mixed-set behavior maxDays already implements via
        // scopes.any { it in AISP_SCOPES }, asserted here so a future refactor of that predicate
        // cannot silently let a marketing scope exempt an AISP scope from its 90-day cap.
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.MARKETING_COMMS_EMAIL),
                accountIbans = listOf("CZ6508000000192000145399"),
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(91),
            )
        }
    }

    // ADR-0205 D1: the disjointness invariant is enforced by a `check()` in Consent's companion
    // `init` block (fails fast at class-load time, not just at code review). Asserted explicitly
    // here too so the invariant has a named, readable test rather than only an implicit one from
    // every other test in this class merely loading the class successfully.
    @Test
    fun `AISP_SCOPES and GDPR_ONLY_SCOPES are disjoint`() {
        assertThat(Consent.AISP_SCOPES.intersect(Consent.GDPR_ONLY_SCOPES)).isEmpty()
    }

    @Test
    fun `GDPR_ONLY_SCOPES contains exactly the GDPR Art7 scopes, not the PSD2 or agent ones`() {
        assertThat(Consent.GDPR_ONLY_SCOPES).containsExactlyInAnyOrder(
            ConsentScope.TELEMETRY_RUM,
            ConsentScope.MARKETING_COMMS_EMAIL,
            ConsentScope.MARKETING_COMMS_PUSH,
            ConsentScope.MARKETING_COMMS_INAPP,
            ConsentScope.CREDIT_OFFERS,
            ConsentScope.CREDIT_PROFILE_USE,
            ConsentScope.CREDIT_AI_AGENT,
        )
    }

    // ADR-0269 rule 1: the credit scopes are Art. 7 data-processing consents like the marketing
    // ones, and must NOT inherit the PSD2 90-day AISP cap or an SCA ceremony.
    @Test
    fun `CREDIT scopes are GDPR-only, uncapped by the 90-day AISP rule and carry no read frequency`() {
        val offers = consent(
            scopes = setOf(ConsentScope.CREDIT_OFFERS),
            accountIbans = null,
            validTo = baseValidFrom.plusDays(180),
        )
        assertThat(offers.frequencyPerDay()).isNull()
        assertThat(offers.hasScope(ConsentScope.CREDIT_OFFERS)).isTrue()
        assertThat(offers.hasScope(ConsentScope.CREDIT_PROFILE_USE)).isFalse()
    }

    // Being shown an offer and having the 360 profile mined to choose it are separate permissions;
    // a single credit consent would make the narrower of the two unexpressable.
    @Test
    fun `CREDIT_OFFERS does not imply CREDIT_PROFILE_USE`() {
        val offersOnly = consent(scopes = setOf(ConsentScope.CREDIT_OFFERS), accountIbans = null)
        assertThat(offersOnly.hasScope(ConsentScope.CREDIT_PROFILE_USE)).isFalse()
    }

    private fun consent(
        scopes: Set<ConsentScope> = setOf(ConsentScope.PAYMENTS_INITIATE),
        accountIbans: List<String>? = listOf("CZ6508000000192000145399"),
        status: ConsentStatus = ConsentStatus.ACTIVE,
        validFrom: OffsetDateTime = baseValidFrom,
        validTo: OffsetDateTime = baseValidFrom.plusDays(1),
    ): Consent = Consent(
        id = UUID.randomUUID(),
        partyId = partyId,
        granteeId = "tpp-123",
        granteeType = GranteeType.TPP,
        granteeName = "Test TPP",
        scopes = scopes,
        accountIbans = accountIbans,
        status = status,
        validFrom = validFrom,
        validTo = validTo,
        scaSessionId = null,
        redirectUri = "https://example.com/redirect",
        tppTransactionId = "txn-1",
        ipAddress = "127.0.0.1",
        userAgent = "JUnit",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )
}
