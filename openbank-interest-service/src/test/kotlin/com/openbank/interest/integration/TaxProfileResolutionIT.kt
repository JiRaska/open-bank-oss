// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.infrastructure.client.AccountDetailClientResponse
import com.openbank.interest.infrastructure.client.AccountServiceClient
import com.openbank.interest.infrastructure.client.PartyAmlProfileClientResponse
import com.openbank.interest.infrastructure.client.PartyDetailClientResponse
import com.openbank.interest.infrastructure.client.PartyServiceClient
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * End-to-end proof of the ADR-0033 §C fast-follow (ADR-0339 B): the REAL `capitalize` chain —
 * real Postgres, real claim, real `WithholdingTaxPolicy` — resolving the beneficiary's tax
 * profile through the REAL [com.openbank.interest.infrastructure.tax.PartyTaxProfileProvider]
 * while account-service and party-service stand in for their fleet contracts at the REST-client
 * boundary (the HTTP layer those clients speak is covered by the Pact consumer contract).
 *
 * This is the test that would have caught the dormant half of #1355: the moment resolution
 * stops being constant, the profile the policy sees must come from party-owned facts — CZ-only
 * residencies withheld at 15 %, a non-resident withheld at the §36 statutory default, a legal
 * entity NOT withheld, and every upstream failure failing safe to withholding, never to a
 * pass-through.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
class TaxProfileResolutionIT {

    @Inject
    lateinit var service: CapitalizeInterestUseCase

    @Inject
    lateinit var configRepo: InterestRateConfigRepository

    @Inject
    lateinit var accrualRepo: InterestAccrualRepository

    @Inject
    lateinit var taxProfilePort: TaxProfilePort

    @Inject
    lateinit var ledger: LedgerBoundary

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    private val periodTo: LocalDate = LocalDate.of(2026, 1, 20)
    private val partyId: UUID = UUID.fromString("61000000-0000-0000-0000-000000000061")

    companion object {
        private const val PRODUCT = "SAVINGS_CZK"
        private lateinit var accounts: AccountServiceClient
        private lateinit var parties: PartyServiceClient

        @BeforeAll
        @JvmStatic
        fun installUpstreamContracts() {
            accounts = mockk()
            parties = mockk()
            // REST clients are @RestClient-qualified beans — the mock must carry the qualifier.
            QuarkusMock.installMockForType(accounts, AccountServiceClient::class.java, RestClient.LITERAL)
            QuarkusMock.installMockForType(parties, PartyServiceClient::class.java, RestClient.LITERAL)
        }
    }

    @BeforeEach
    fun resetLedger() {
        ledger.reset()
    }

    @Test
    fun `a CZ-resident individual is withheld at 15 percent, frozen with the claim`() {
        val accountId = UUID.randomUUID()
        stubIndividual(accountId, residencies = listOf("CZ"))
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        assertThat(cap.grossAmount).isEqualByComparingTo("100.00")
        assertThat(cap.taxAmount).isEqualByComparingTo("15")
        assertThat(cap.netAmount).isEqualByComparingTo("85.00")
        // The claim froze exactly the profile the policy saw — a resident individual.
        assertThat(frozenProfile(accountId)).isEqualTo(
            TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.RESIDENT),
        )
    }

    @Test
    fun `a non-resident individual is withheld at the statutory default rate`() {
        val accountId = UUID.randomUUID()
        // ADR-0339 B: treaty rates are not stored yet, so the §36 default applies to every
        // non-resident — the conservative path, never a pass-through.
        stubIndividual(accountId, residencies = listOf("CZ", "DE"))
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        assertThat(cap.taxAmount).isEqualByComparingTo("15")
        assertThat(cap.netAmount).isEqualByComparingTo("85.00")
        assertThat(frozenProfile(accountId)).isEqualTo(
            TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.NON_RESIDENT),
        )
    }

    @Test
    fun `a legal-entity beneficiary is not withheld, and the AML profile is never read`() {
        val accountId = UUID.randomUUID()
        every { accounts.getAccount(accountId) } returns Uni.createFrom().item(
            AccountDetailClientResponse(id = accountId, partyId = partyId),
        )
        every { parties.getParty(partyId) } returns Uni.createFrom().item(
            PartyDetailClientResponse(id = partyId, partyType = "COMPANY"),
        )
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        // §36: interest enters the CIT base gross — credited whole, no withholding recorded.
        assertThat(cap.taxAmount).isEqualByComparingTo("0")
        assertThat(cap.netAmount).isEqualByComparingTo(cap.grossAmount)
        assertThat(frozenProfile(accountId)).isEqualTo(
            TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT),
        )
    }

    @Test
    fun `an unreachable party-service fails safe to withholding, never to a pass-through`() {
        val accountId = UUID.randomUUID()
        every { accounts.getAccount(accountId) } returns
            Uni.createFrom().failure(RuntimeException("party-service unreachable"))
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        // ADR-0033 §C: the conservative CZ-resident-individual default — an over-withholding is
        // correctable by the taxpayer; an under-withholding would be a bank liability. The
        // capitalization must NOT be skipped or error out.
        assertThat(cap.taxAmount).isEqualByComparingTo("15")
        assertThat(cap.netAmount).isEqualByComparingTo("85.00")
        assertThat(frozenProfile(accountId)).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
    }

    @Test
    fun `an account whose party never declared a profile fails safe to withholding`() {
        val accountId = UUID.randomUUID()
        every { accounts.getAccount(accountId) } returns Uni.createFrom().item(
            AccountDetailClientResponse(id = accountId, partyId = partyId),
        )
        every { parties.getParty(partyId) } returns Uni.createFrom().item(
            PartyDetailClientResponse(id = partyId, partyType = "INDIVIDUAL"),
        )
        every { parties.getAmlProfile(partyId) } returns Uni.createFrom().failure(
            WebApplicationException(Response.status(404).build()),
        )
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        assertThat(cap.taxAmount).isEqualByComparingTo("15")
        assertThat(frozenProfile(accountId)).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun stubIndividual(accountId: UUID, residencies: List<String>) {
        every { accounts.getAccount(accountId) } returns Uni.createFrom().item(
            AccountDetailClientResponse(id = accountId, partyId = partyId),
        )
        every { parties.getParty(partyId) } returns Uni.createFrom().item(
            PartyDetailClientResponse(id = partyId, partyType = "INDIVIDUAL"),
        )
        every { parties.getAmlProfile(partyId) } returns Uni.createFrom().item(
            PartyAmlProfileClientResponse(taxResidencies = residencies, usPerson = false),
        )
    }

    /** The profile the claim froze, rebuilt from the entity's claimed_* columns (V13, #1355). */
    private fun frozenProfile(accountId: UUID): TaxProfile? {
        val e = accrualsOf(accountId).single()
        val type = e.claimedTaxpayerType ?: return null
        return TaxProfile(
            taxpayerType = type,
            residency = e.claimedResidency!!,
            treatyRate = e.claimedTreatyRate,
            nonCooperatingState = e.claimedNonCooperatingState ?: false,
            exemptCode = e.claimedExemptCode,
        )
    }

    private fun capitalize(accountId: UUID) =
        VertxContextSupport.subscribeAndAwait { service.capitalize(accountId, PRODUCT, periodTo) }!!

    private fun persistConfig(productId: String, annualRate: BigDecimal): UUID = VertxContextSupport.subscribeAndAwait {
        configRepo.save(
            com.openbank.interest.domain.model.InterestRateConfig(
                productId = productId,
                currency = "CZK",
                annualRate = annualRate,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }!!.id

    /** interest_accruals.config_id is a real FK, so every accrual needs a rate config to point at. */
    private fun persistAccrual(accountId: UUID, accrued: String, date: LocalDate) {
        val config = persistConfig(PRODUCT, BigDecimal("0.365000"))
        val entity = InterestAccrualEntity().apply {
            id = UUID.randomUUID()
            this.accountId = accountId
            productId = PRODUCT
            configId = config
            accrualDate = date
            balance = BigDecimal("1000.00")
            dailyRate = BigDecimal("0.0010000000")
            accruedAmount = BigDecimal(accrued)
            currency = "CZK"
            status = AccrualStatus.ACCRUING
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait { sf.withTransaction { s -> s.persist(entity) } }
    }

    private fun accrualsOf(accountId: UUID): List<InterestAccrualEntity> = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery("FROM InterestAccrualEntity WHERE accountId = :a", InterestAccrualEntity::class.java)
                .setParameter("a", accountId).resultList
        }
    }!!
}
