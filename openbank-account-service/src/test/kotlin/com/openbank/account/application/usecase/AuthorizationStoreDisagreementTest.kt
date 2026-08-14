// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.DelegatedPaymentOutcome
import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.AuthorizationRole
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.libs.domain.money.Money
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The dual-run's real question: **can the two stores disagree, and does anyone find out?**
 * (ADR-0232 D1, issue #2993.)
 *
 * `account_authorizations` and the delegation projection are two stores answering one access
 * question, and nothing writes both — `AuthorizationResource` writes only the legacy table,
 * `DelegationEventConsumer` only the projection. `authorizeDelegatedPayment` ORs them, so a
 * revocation in either store leaves the other one authorising a debit. That is a deliberate
 * choice (delegation "only ever ADDS access"), and it was chosen with no instrument attached:
 * before this test the divergence was invisible in both directions, at a decision that moves
 * money.
 *
 * These tests assert against a REAL [SimpleMeterRegistry], not a verified mock. A mock would
 * pass against a metric name nothing scrapes; the registry is asked for the series an operator
 * would actually query, tag by tag.
 *
 * The verdict assertions in every case are the other half. A telemetry change that quietly moved
 * an authorization decision would be far worse than the blindness it replaced, so each test pins
 * the outcome as well as the counter.
 */
class AuthorizationStoreDisagreementTest {

    private val accountRepository: AccountRepository = mockk()
    private val authorizationRepository: AccountAuthorizationRepository = mockk()
    private val projectionRepository: DelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC)
    private val registry: MeterRegistry = SimpleMeterRegistry()
    private lateinit var service: AuthorizationService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        val metrics = DomainMetrics().apply { registryInstance = instance }

        service = AuthorizationService(
            accountRepository,
            authorizationRepository,
            projectionRepository,
            metrics,
            clock,
        )
        coEvery { accountRepository.findById(accountId) } returns
            mockk<Account>().also { every { it.partyId } returns owner }
        coEvery { authorizationRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
        coEvery { projectionRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
    }

    /** The series an operator would query, or null when it was never registered. */
    private fun disagreements(direction: String): Double? = registry
        .find("openbank.authz.store_disagreement")
        .tag("question", "account_delegated_payment")
        .tag("direction", direction)
        .counter()
        ?.count()

    private fun legacyRow(transactionLimit: Money? = null) = listOf(
        AccountAuthorization(
            accountId = accountId,
            partyId = delegate,
            role = AuthorizationRole.PAYMENT_ONLY,
            dailyLimit = null,
            transactionLimit = transactionLimit,
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = null,
            grantedBy = owner,
            grantedAt = Instant.now(clock),
        ),
    )

    private fun projectionGrant(perTxAmount: String? = null) = listOf(
        DelegatedAccessGrant(
            id = UUID.randomUUID(),
            accountId = accountId,
            grantorPartyId = owner,
            granteePartyId = delegate,
            capabilities = setOf(DelegatedAccessGrant.CAP_INITIATE_PAYMENT),
            perTransactionLimitAmount = perTxAmount?.toBigDecimal(),
            perTransactionLimitCurrency = perTxAmount?.let { "CZK" },
            validFrom = now.minusDays(1),
            validTo = now.plusDays(30),
            active = true,
        ),
    )

    private fun czk(v: String) = Money.of(v, "CZK")

    // ── the two directions a revocation can strand ────────────────────────────────────────

    /**
     * `DELETE /authorizations/{id}` closed the legacy row; the delegation grant is still ACTIVE.
     * Money moves under the projection, and the operator who revoked believes it stopped.
     */
    @Test
    fun `a grant live only in the delegation projection is recorded as delegation_only`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns projectionGrant()

        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))

        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.DELEGATED)
        assertThat(disagreements("delegation_only")).isEqualTo(1.0)
        assertThat(disagreements("legacy_only")).isNull()
    }

    /**
     * The mirror: `DelegationRevoked` closed the projection row, the legacy `AccountAuthorization`
     * still grants. This is the direction the migration is supposed to retire, and the one the
     * money path answers `authorized: true` on.
     */
    @Test
    fun `a grant live only in the legacy table is recorded as legacy_only`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns legacyRow()

        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))

        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.LEGACY_AUTHORIZATION)
        assertThat(disagreements("legacy_only")).isEqualTo(1.0)
        assertThat(disagreements("delegation_only")).isNull()
    }

    /**
     * A disagreement is about the ANSWER, not about row presence. Both stores hold a grant, but
     * only the legacy ceiling admits this amount — so the two genuinely differ on this decision
     * and that is what gets counted. Counting rows instead would have missed it.
     */
    @Test
    fun `differing ceilings on the same amount are a disagreement`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns legacyRow()
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            projectionGrant(perTxAmount = "100.00")

        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))

        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.LEGACY_AUTHORIZATION)
        assertThat(disagreements("legacy_only")).isEqualTo(1.0)
    }

    // ── the controls: a counter that fires on agreement measures nothing ───────────────────

    @Test
    fun `both stores permitting is not a disagreement`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns legacyRow()
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns projectionGrant()

        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00")).authorized).isTrue()

        assertThat(disagreements("legacy_only")).isNull()
        assertThat(disagreements("delegation_only")).isNull()
    }

    @Test
    fun `both stores refusing is not a disagreement`(): Unit = runBlocking {
        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))

        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
        assertThat(disagreements("legacy_only")).isNull()
        assertThat(disagreements("delegation_only")).isNull()
    }

    /**
     * The owner short-circuits before either store is consulted, so their payment must not
     * register as a divergence. Without this the counter would track account traffic — the
     * "zero from an empty population" trap in reverse, a non-zero that means nothing.
     */
    @Test
    fun `an owner's own payment is never a disagreement`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, owner) } returns legacyRow()

        assertThat(service.authorizeDelegatedPayment(accountId, owner, czk("1000.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.OWNER)
        assertThat(disagreements("legacy_only")).isNull()
        assertThat(disagreements("delegation_only")).isNull()
    }
}
