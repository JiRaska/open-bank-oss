// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.BorrowerDistressPort
import com.openbank.lending.application.port.out.CreditOffersConsentPort
import com.openbank.lending.application.usecase.CreditOfferEligibilityService
import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * ADR-0269 rule 4 at the transport seam.
 *
 * The pricing math is proved in `CreditQuoteCalculatorTest`; what is proved here is the part that
 * math cannot see — that an unauthorised caller gets no price, that a customer in distress gets a
 * reason code instead of a tailored instalment, and that a *consent-less* customer who ASKED still
 * gets an answer, because pull-only means the bank does not initiate, not that it stonewalls.
 */
class CustomerQuoteResourceTest {

    private val edge = "service-account-openbank-edge"
    private val party = UUID.fromString("05a02ef1-381c-40e7-b73f-d6855eead42e")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)

    private val healthy = BorrowerDistressSignals(
        hasArrears = false,
        hasNegativeBalance = false,
        hasEnforcementOrder = false,
        hasInsolvencyProceeding = false,
        inHardshipArrangement = false,
        lastAffordabilityFailureAt = null,
        bufferDays = 90,
        lastCreditContactAt = null,
        inputsChangedSinceLastContact = true,
        complete = true,
    )

    private fun config(
        enabled: Boolean = true,
        caller: String? = "service-account-openbank-edge",
        rate: BigDecimal? = BigDecimal("0.079"),
    ) = CustomerIntakeConfig(
        enabled = enabled,
        callerPrincipal = Optional.ofNullable(caller),
        jurisdiction = "CZ",
        productType = "CONSUMER_CREDIT",
        currency = "CZK",
        nominalAnnualRate = Optional.ofNullable(rate),
        minAmount = BigDecimal("5000"),
        maxAmount = BigDecimal("1000000"),
        minTermMonths = 6,
        maxTermMonths = 120,
    )

    private fun eligibility(consent: Boolean = false, signals: BorrowerDistressSignals = healthy) =
        CreditOfferEligibilityService(
            consent = object : CreditOffersConsentPort {
                override suspend fun hasCreditOffersConsent(partyId: UUID) = consent
            },
            distress = object : BorrowerDistressPort {
                override suspend fun signalsFor(partyId: UUID) = signals
            },
            clock = clock,
        )

    private fun resource(
        config: CustomerIntakeConfig = config(),
        principal: String = edge,
        service: CreditOfferEligibilityService = eligibility(),
    ) = CustomerQuoteResource(config, service, identity(principal), clock)

    private fun identity(name: String): SecurityIdentity =
        QuarkusSecurityIdentity.builder().setPrincipal(Principal { name }).build()

    private fun ask(
        resource: CustomerQuoteResource,
        party: String? = this.party.toString(),
        amount: String = "250000",
        term: Int = 48,
    ) = resource.quote(party, CustomerQuoteRequest(BigDecimal(amount), term)).await().indefinitely()

    private fun quoteOf(response: jakarta.ws.rs.core.Response) = response.entity as CustomerQuoteDto

    @Suppress("UNCHECKED_CAST")
    private fun errorOf(response: jakarta.ws.rs.core.Response) = response.entity as Map<String, String>

    // ── Pull without consent still gets an answer ─────────────────────────────

    @Test
    fun `a customer who asked is priced even with no credit_offers consent`() {
        // The pull-only rule says the bank must not INITIATE. Refusing to answer a question the
        // customer just asked would be the same dark pattern pointing the other way.
        val response = ask(resource(service = eligibility(consent = false)))
        assertThat(response.status).isEqualTo(200)
        assertThat(quoteOf(response).monthlyPayment).isNotBlank()
    }

    // ── Distress refuses even a pull ──────────────────────────────────────────

    @Test
    fun `a customer in arrears gets a reason code, not a tailored instalment`() {
        val response = ask(resource(service = eligibility(signals = healthy.copy(hasArrears = true))))
        assertThat(response.status).isEqualTo(409)
        assertThat(errorOf(response)["reasonCode"]).isEqualTo("ARREARS")
    }

    @Test
    fun `a suppressed quote carries no price fields at all`() {
        val response = ask(resource(service = eligibility(signals = healthy.copy(hasArrears = true))))
        // A 200 with empty numbers is what a client renders as "0". The refusal must not be
        // shaped like a quote.
        assertThat(response.entity).isNotInstanceOf(CustomerQuoteDto::class.java)
    }

    @Test
    fun `unreadable distress signals refuse the price rather than pricing optimistically`() {
        val response = ask(resource(service = eligibility(signals = healthy.copy(complete = false))))
        assertThat(response.status).isEqualTo(409)
        assertThat(errorOf(response)["reasonCode"]).isEqualTo("SIGNALS_UNAVAILABLE")
    }

    @Test
    fun `contact frequency does not silence a price the customer asked for`() {
        val contactedYesterday = healthy.copy(lastCreditContactAt = clock.instant().minusSeconds(86_400))
        val response = ask(resource(service = eligibility(signals = contactedYesterday)))
        assertThat(response.status).isEqualTo(200)
    }

    // ── Caller boundary ───────────────────────────────────────────────────────

    @Test
    fun `an operator that is not the edge gets no price`() {
        assertThat(ask(resource(principal = "service-account-someone-else")).status).isEqualTo(403)
    }

    @Test
    fun `an unset caller-principal refuses everyone`() {
        assertThat(ask(resource(config = config(caller = null))).status).isEqualTo(403)
    }

    @Test
    fun `a missing party header is refused`() {
        assertThat(ask(resource(), party = null).status).isEqualTo(400)
    }

    @Test
    fun `an unconfigured price refuses rather than quoting zero interest`() {
        assertThat(ask(resource(config = config(rate = null))).status).isEqualTo(403)
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    @Test
    fun `an amount outside the product bounds is refused, not quoted`() {
        assertThat(ask(resource(), amount = "4999").status).isEqualTo(400)
        assertThat(ask(resource(), amount = "1000001").status).isEqualTo(400)
    }

    @Test
    fun `a term outside the product bounds is refused`() {
        assertThat(ask(resource(), term = 5).status).isEqualTo(400)
        assertThat(ask(resource(), term = 121).status).isEqualTo(400)
    }

    // ── The quote says what it is ─────────────────────────────────────────────

    @Test
    fun `every quote states that it is not binding and carries an expiry`() {
        val dto = quoteOf(ask(resource()))
        assertThat(dto.binding).isFalse()
        assertThat(dto.validUntil).isNotBlank()
        assertThat(dto.aprcPercent).isNotNull()
    }
}
