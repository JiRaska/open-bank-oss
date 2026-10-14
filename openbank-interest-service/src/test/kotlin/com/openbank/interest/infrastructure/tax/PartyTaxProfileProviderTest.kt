// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.tax

import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.infrastructure.client.AccountDetailClientResponse
import com.openbank.interest.infrastructure.client.AccountServiceClient
import com.openbank.interest.infrastructure.client.PartyAmlProfileClientResponse
import com.openbank.interest.infrastructure.client.PartyDetailClientResponse
import com.openbank.interest.infrastructure.client.PartyServiceClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.UUID

/**
 * Unit tests for [PartyTaxProfileProvider] — the ADR-0033 §C fast-follow that resolves the
 * withholding profile from party-owned facts (ADR-0339 B). The contract under test is the
 * port's: **never propagate a resolution failure** — every 404/422/timeout/5xx resolves to
 * the fiscally conservative default, because an over-withholding is correctable by the
 * taxpayer and an under-withholding is a bank liability.
 */
class PartyTaxProfileProviderTest {

    private val accountClient = mockk<AccountServiceClient>()
    private val partyClient = mockk<PartyServiceClient>()
    private val registry = SimpleMeterRegistry()
    private val registryInstance = mockk<Instance<MeterRegistry>> {
        every { isResolvable } returns true
        every { get() } returns registry
    }

    private val provider = PartyTaxProfileProvider(accountClient, partyClient, registryInstance)

    private val accountId: UUID = UUID.randomUUID()
    private val partyId: UUID = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        clearMocks(accountClient, partyClient)
        registry.clear()
    }

    // --- The three resolution paths --------------------------------------------------------------

    @Test
    fun `CZ-only residencies resolve to a resident individual`() {
        stubAccount(partyId)
        stubParty("INDIVIDUAL")
        every { partyClient.getAmlProfile(partyId) } returns Uni.createFrom().item(
            PartyAmlProfileClientResponse(taxResidencies = listOf("CZ"), usPerson = false),
        )

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile.taxpayerType).isEqualTo(TaxpayerType.INDIVIDUAL)
        assertThat(profile.residency).isEqualTo(TaxResidency.RESIDENT)
        assertThat(profile.treatyRate).isNull()
        assertThat(profile.exemptCode).isNull()
        assertThat(failSafeCount()).isZero()
    }

    @Test
    fun `any non-CZ residency resolves to a non-resident individual`() {
        stubAccount(partyId)
        stubParty("INDIVIDUAL")
        every { partyClient.getAmlProfile(partyId) } returns Uni.createFrom().item(
            PartyAmlProfileClientResponse(taxResidencies = listOf("CZ", "DE"), usPerson = false),
        )

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile.residency).isEqualTo(TaxResidency.NON_RESIDENT)
        assertThat(failSafeCount()).isZero()
    }

    @Test
    fun `a non-individual party resolves to legal entity without touching the AML profile`() {
        stubAccount(partyId)
        stubParty("COMPANY")

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile.taxpayerType).isEqualTo(TaxpayerType.LEGAL_ENTITY)
        verify(exactly = 0) { partyClient.getAmlProfile(any()) }
        assertThat(failSafeCount()).isZero()
    }

    @Test
    fun `sole trader and trust follow the legal-entity path`() {
        for (type in listOf("SOLE_TRADER", "TRUST")) {
            clearMocks(accountClient, partyClient)
            stubAccount(partyId)
            stubParty(type)

            val profile = provider.resolve(accountId).await().indefinitely()

            assertThat(profile.taxpayerType).isEqualTo(TaxpayerType.LEGAL_ENTITY)
            verify(exactly = 0) { partyClient.getAmlProfile(any()) }
        }
    }

    // --- Fail-safe: every failure resolves to the conservative default ---------------------------

    @Test
    fun `account without a partyId fails safe`() {
        every { accountClient.getAccount(accountId) } returns Uni.createFrom().item(
            AccountDetailClientResponse(id = accountId, partyId = null),
        )

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
        assertThat(failSafeCount()).isEqualTo(1.0)
    }

    @Test
    fun `account lookup 404 fails safe`() {
        every { accountClient.getAccount(accountId) } returns httpFailure(404)

        val profile = assertDoesNotThrow { provider.resolve(accountId).await().indefinitely() }

        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
        assertThat(failSafeCount()).isEqualTo(1.0)
    }

    @Test
    fun `party lookup 404 fails safe`() {
        stubAccount(partyId)
        every { partyClient.getParty(partyId) } returns httpFailure(404)

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
        assertThat(failSafeCount()).isEqualTo(1.0)
    }

    @Test
    fun `undeclared AML profile fails safe to withholding`() {
        stubAccount(partyId)
        stubParty("INDIVIDUAL")
        every { partyClient.getAmlProfile(partyId) } returns httpFailure(404)

        val profile = provider.resolve(accountId).await().indefinitely()

        // Never under-withhold: no declaration means the conservative CZ-resident-individual default,
        // not a pass-through.
        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
        assertThat(failSafeCount()).isEqualTo(1.0)
    }

    @Test
    fun `AML profile 422 fails safe`() {
        stubAccount(partyId)
        stubParty("INDIVIDUAL")
        every { partyClient.getAmlProfile(partyId) } returns httpFailure(422)

        val profile = provider.resolve(accountId).await().indefinitely()

        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
    }

    @Test
    fun `an upstream transport failure fails safe without throwing`() {
        stubAccount(partyId)
        stubParty("INDIVIDUAL")
        every { partyClient.getAmlProfile(partyId) } returns
            Uni.createFrom().failure(RuntimeException("connection reset"))

        val profile = assertDoesNotThrow { provider.resolve(accountId).await().indefinitely() }

        assertThat(profile).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
        assertThat(failSafeCount()).isEqualTo(1.0)
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun stubAccount(partyId: UUID?) {
        every { accountClient.getAccount(accountId) } returns Uni.createFrom().item(
            AccountDetailClientResponse(id = accountId, partyId = partyId),
        )
    }

    private fun stubParty(type: String) {
        every { partyClient.getParty(partyId) } returns Uni.createFrom().item(
            PartyDetailClientResponse(id = partyId, partyType = type),
        )
    }

    private fun <T> httpFailure(status: Int): Uni<T> = Uni.createFrom().failure(
        WebApplicationException(Response.status(status).build()),
    )

    private fun failSafeCount(): Double =
        registry.find("openbank.interest.tax_profile_resolution.fail_safe.total").counter()?.count() ?: 0.0
}
