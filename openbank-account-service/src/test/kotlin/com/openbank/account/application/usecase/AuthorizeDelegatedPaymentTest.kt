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
import io.mockk.coEvery
import io.mockk.mockk
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
 * `authorizeDelegatedPayment` — the decision the money path now consults (ADR-0232 D3/D5).
 *
 * The cases that matter here are the ones a boolean guard could not express: which grant
 * permitted the debit, whose account it was taken on behalf of, and whether a refusal was
 * "no grant" or "the grantor's ceiling bit". The audit record is built from exactly those,
 * so a wrong answer here is a wrong entry in the tamper-evident chain, not just a 403.
 */
class AuthorizeDelegatedPaymentTest {

    private val accountRepository: AccountRepository = mockk()
    private val authorizationRepository: AccountAuthorizationRepository = mockk()
    private val projectionRepository: DelegationProjectionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: AuthorizationService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val stranger: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = AuthorizationService(accountRepository, authorizationRepository, projectionRepository, clock)
        coEvery { accountRepository.findById(accountId) } returns account()
        coEvery { authorizationRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
        coEvery { projectionRepository.findActiveByAccountAndParty(any(), any()) } returns emptyList()
    }

    private fun account(): Account = mockk<Account>().also { io.mockk.every { it.partyId } returns owner }

    private fun grant(
        capabilities: Set<String> = setOf(DelegatedAccessGrant.CAP_INITIATE_PAYMENT),
        perTxAmount: String? = null,
        perTxCurrency: String = "CZK",
        grantorPartyId: UUID = owner,
        validFrom: OffsetDateTime = now.minusDays(1),
        validTo: OffsetDateTime? = now.plusDays(30),
        active: Boolean = true,
    ) = DelegatedAccessGrant(
        id = UUID.randomUUID(),
        accountId = accountId,
        grantorPartyId = grantorPartyId,
        granteePartyId = delegate,
        capabilities = capabilities,
        perTransactionLimitAmount = perTxAmount?.toBigDecimal(),
        perTransactionLimitCurrency = perTxAmount?.let { perTxCurrency },
        validFrom = validFrom,
        validTo = validTo,
        active = active,
    )

    private fun legacy(role: AuthorizationRole, transactionLimit: Money?) = AccountAuthorization(
        accountId = accountId,
        partyId = delegate,
        role = role,
        dailyLimit = null,
        transactionLimit = transactionLimit,
        validFrom = LocalDate.of(2026, 1, 1),
        validTo = null,
        grantedBy = owner,
        grantedAt = Instant.now(clock),
    )

    private fun czk(v: String) = Money.of(v, "CZK")

    // ── the authorising outcomes ───────────────────────────────────────────────────────────

    @Test
    fun `owner is OWNER, not DELEGATED, and carries no grant evidence`(): Unit = runBlocking {
        val d = service.authorizeDelegatedPayment(accountId, owner, czk("1000.00"))
        assertThat(d.authorized).isTrue()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.OWNER)
        // An owner's payment must never be recorded as on-behalf-of anyone.
        assertThat(d.delegationId).isNull()
        assertThat(d.grantorPartyId).isNull()
    }

    @Test
    fun `delegate within the ceiling is DELEGATED and names the grant and the grantor`(): Unit = runBlocking {
        val g = grant(perTxAmount = "5000.00")
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns listOf(g)
        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))
        assertThat(d.authorized).isTrue()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.DELEGATED)
        assertThat(d.delegationId).isEqualTo(g.id)
        assertThat(d.grantorPartyId).isEqualTo(owner)
    }

    @Test
    fun `the grant named is the one that permitted it, not merely the first candidate`(): Unit = runBlocking {
        val tooSmall = grant(perTxAmount = "100.00")
        val permitting = grant(perTxAmount = "5000.00")
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(tooSmall, permitting)
        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00"))
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.DELEGATED)
        assertThat(d.delegationId).isEqualTo(permitting.id)
        assertThat(d.delegationId).isNotEqualTo(tooSmall.id)
    }

    @Test
    fun `an unlimited grant permits any amount`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns listOf(grant())
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("9999999.00")).authorized).isTrue()
    }

    @Test
    fun `FULL_ACCESS capabilities satisfy the payment question`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(capabilities = DelegatedAccessGrant.FULL_ACCESS_CAPABILITIES))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.DELEGATED)
    }

    // ── the refusals, and why they are distinguished ───────────────────────────────────────

    @Test
    fun `a party with nothing on the account is NO_GRANT`(): Unit = runBlocking {
        val d = service.authorizeDelegatedPayment(accountId, stranger, czk("10.00"))
        assertThat(d.authorized).isFalse()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    @Test
    fun `over the ceiling is LIMIT_EXCEEDED, not NO_GRANT`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(perTxAmount = "500.00"))
        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("500.01"))
        assertThat(d.authorized).isFalse()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.LIMIT_EXCEEDED)
        // A refusal must not leak which grant exists.
        assertThat(d.delegationId).isNull()
        assertThat(d.grantorPartyId).isNull()
    }

    @Test
    fun `exactly at the ceiling passes`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(perTxAmount = "500.00"))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("500.00")).authorized).isTrue()
    }

    @Test
    fun `a ceiling in another currency refuses rather than compares numbers`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(perTxAmount = "500.00", perTxCurrency = "EUR"))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.LIMIT_EXCEEDED)
    }

    @Test
    fun `a read-only grant cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(capabilities = setOf(DelegatedAccessGrant.CAP_READ_BALANCES)))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    @Test
    fun `an expired grant cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(validTo = now.minusMinutes(1)))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    @Test
    fun `a not-yet-valid grant cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(validFrom = now.plusDays(1), validTo = now.plusDays(30)))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    @Test
    fun `a closed grant cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(active = false))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    /**
     * The hole `issuedBy` exists to close, re-asserted at the money path: a grant naming this
     * account but issued by somebody who does not own it is not authority over this account.
     * Two colluding parties can produce such a row with nothing but their own valid SCA.
     */
    @Test
    fun `a grant issued by a non-owner cannot pay`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(grantorPartyId = stranger))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    @Test
    fun `an unknown account is ACCOUNT_NOT_FOUND and not authorized`(): Unit = runBlocking {
        val missing = UUID.randomUUID()
        coEvery { accountRepository.findById(missing) } returns null
        val d = service.authorizeDelegatedPayment(missing, delegate, czk("10.00"))
        assertThat(d.authorized).isFalse()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.ACCOUNT_NOT_FOUND)
    }

    // ── the legacy authorization table ─────────────────────────────────────────────────────

    @Test
    fun `a legacy PAYMENT_ONLY row still authorises`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(legacy(AuthorizationRole.PAYMENT_ONLY, transactionLimit = null))
        val d = service.authorizeDelegatedPayment(accountId, delegate, czk("10.00"))
        assertThat(d.authorized).isTrue()
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.LEGACY_AUTHORIZATION)
    }

    @Test
    fun `a legacy CARD_HOLDER row does not authorise a payment`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(legacy(AuthorizationRole.CARD_HOLDER, transactionLimit = null))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("10.00")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.NO_GRANT)
    }

    /**
     * The deliberate divergence from `isAuthorizedForAmount`, which ignores this column entirely.
     * Wiring that behaviour to a live debit route would have made an operator-set ceiling
     * decoration. This test is the reason the divergence is safe to claim.
     */
    @Test
    fun `a legacy row's own transactionLimit is enforced`(): Unit = runBlocking {
        coEvery { authorizationRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(legacy(AuthorizationRole.PAYMENT_ONLY, transactionLimit = czk("1000.00")))
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("1000.00")).authorized).isTrue()
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("1000.01")).authorized).isFalse()
        assertThat(service.authorizeDelegatedPayment(accountId, delegate, czk("1000.01")).outcome)
            .isEqualTo(DelegatedPaymentOutcome.LIMIT_EXCEEDED)
        // …and the old method still says yes, which is exactly why the new one is separate.
        assertThat(
            service.isAuthorizedForAmount(
                accountId,
                delegate,
                AuthorizationRole.PAYMENT_ONLY,
                czk("1000.01"),
            ),
        ).isTrue()
    }

    @Test
    fun `a null amount asks the capability question without a ceiling`(): Unit = runBlocking {
        coEvery { projectionRepository.findActiveByAccountAndParty(accountId, delegate) } returns
            listOf(grant(perTxAmount = "1.00"))
        val d = service.authorizeDelegatedPayment(accountId, delegate, null)
        assertThat(d.outcome).isEqualTo(DelegatedPaymentOutcome.DELEGATED)
    }
}
