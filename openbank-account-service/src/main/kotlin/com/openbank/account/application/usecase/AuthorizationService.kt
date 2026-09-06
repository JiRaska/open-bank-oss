// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.DelegatedPaymentDecision
import com.openbank.account.application.port.`in`.DelegatedPaymentOutcome
import com.openbank.account.application.port.`in`.GrantAuthorizationCommand
import com.openbank.account.application.port.`in`.ListAuthorizationsQuery
import com.openbank.account.application.port.`in`.RevokeAuthorizationCommand
import com.openbank.account.application.port.out.AccountAuthorizationRepository
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.AccountAccessEntry
import com.openbank.account.domain.model.AccountAccessSource
import com.openbank.account.domain.model.AccountAuthorization
import com.openbank.account.domain.model.AuthorizationRole
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class AuthorizationNotFoundException(id: UUID) : RuntimeException("Authorization not found: $id")
class AuthorizationNotOnAccountException(authId: UUID, accountId: UUID) :
    RuntimeException("Authorization $authId does not belong to account $accountId")

@ApplicationScoped
class AuthorizationService(
    private val accountRepository: AccountRepository,
    private val authorizationRepository: AccountAuthorizationRepository,
    private val delegationProjectionRepository: DelegationProjectionRepository,
    private val metrics: DomainMetrics,
    private val clock: Clock,
) : AuthorizationUseCase {

    private val log = Logger.getLogger(AuthorizationService::class.java)

    override suspend fun grantAuthorization(command: GrantAuthorizationCommand): AccountAuthorization {
        accountRepository.findById(command.accountId)
            ?: throw AccountNotFoundException("Account not found: ${command.accountId}")

        val auth = AccountAuthorization(
            accountId = command.accountId,
            partyId = command.partyId,
            role = command.role,
            dailyLimit = command.dailyLimit,
            transactionLimit = command.transactionLimit,
            validFrom = command.validFrom,
            validTo = command.validTo,
            grantedBy = command.grantedBy,
            grantedAt = Instant.now(clock),
        )

        return authorizationRepository.save(auth)
    }

    override suspend fun revokeAuthorization(command: RevokeAuthorizationCommand): AccountAuthorization {
        val auth = authorizationRepository.findById(command.authorizationId)
            ?: throw AuthorizationNotFoundException(command.authorizationId)

        if (auth.accountId != command.accountId) {
            throw AuthorizationNotOnAccountException(command.authorizationId, command.accountId)
        }

        return authorizationRepository.save(auth.revoke(command.revokedBy, command.reason, clock))
    }

    override suspend fun listAuthorizations(query: ListAuthorizationsQuery): List<AccountAuthorization> =
        authorizationRepository.findByAccountId(query.accountId)

    /**
     * The owner-facing mirror of the payment guard (see [authorizeDelegatedPayment]).
     *
     * Reads the SAME two stores, in the same order, with the same active/validity filters. It is
     * written as a projection of the guard on purpose: an independently computed transparency view
     * drifts from enforcement the first time either store changes, and the view is the half that
     * never gets exercised by a real debit.
     *
     * `canInitiatePayments` answers the only question an owner actually cares about — can this
     * person take money out — rather than restating a role name the customer never chose.
     */
    override suspend fun effectiveAccess(accountId: UUID): List<AccountAccessEntry> {
        // No such account: an empty list, not an exception. A 404-vs-200 difference here would let
        // any caller probe which account ids exist.
        val account = accountRepository.findById(accountId) ?: return emptyList()
        val now = OffsetDateTime.now(clock)
        val today = now.toLocalDate()

        val owner = AccountAccessEntry(
            partyId = account.partyId,
            source = AccountAccessSource.OWNER,
            canInitiatePayments = true,
            capabilities = setOf(DelegatedAccessGrant.CAP_INITIATE_PAYMENT),
        )

        val mandates = authorizationRepository.findByAccountId(accountId)
            .filter { it.isActiveOn(today) }
            .map { auth ->
                AccountAccessEntry(
                    partyId = auth.partyId,
                    source = AccountAccessSource.BANK_MANDATE,
                    // Same disjunct the guard uses: PAYMENT_ONLY or FULL_ACCESS may debit.
                    canInitiatePayments = auth.role == AuthorizationRole.PAYMENT_ONLY ||
                        auth.role == AuthorizationRole.FULL_ACCESS,
                    capabilities = setOf(auth.role.name),
                    perTransactionLimit = auth.transactionLimit?.amount,
                    perTransactionLimitCurrency = auth.transactionLimit?.currency?.code,
                    validFrom = auth.validFrom.atStartOfDay().atOffset(now.offset),
                    validTo = auth.validTo?.atStartOfDay()?.atOffset(now.offset),
                )
            }

        val delegations = delegationProjectionRepository.findActiveByAccount(accountId)
            // issuedBy: a grant only speaks for this account if its grantor owns it. Dropping the
            // check would let a grant naming someone else's account appear in this owner's view.
            .filter { it.issuedBy(account.partyId) && it.isActiveOn(now) }
            .map { grant ->
                AccountAccessEntry(
                    partyId = grant.granteePartyId,
                    source = AccountAccessSource.CUSTOMER_DELEGATION,
                    canInitiatePayments = grant.satisfies(AuthorizationRole.PAYMENT_ONLY),
                    capabilities = grant.capabilities,
                    perTransactionLimit = grant.perTransactionLimitAmount,
                    perTransactionLimitCurrency = grant.perTransactionLimitCurrency,
                    validFrom = grant.validFrom,
                    validTo = grant.validTo,
                    grantId = grant.id,
                )
            }

        return listOf(owner) + mandates + delegations
    }

    override suspend fun isAuthorized(accountId: UUID, partyId: UUID, role: AuthorizationRole): Boolean {
        val account = accountRepository.findById(accountId) ?: return false
        if (account.partyId == partyId) return true
        val active = authorizationRepository.findActiveByAccountAndParty(accountId, partyId)
        if (active.any { it.role == role || it.role == AuthorizationRole.FULL_ACCESS }) return true
        return hasDelegatedAccess(accountId, partyId, role, account.partyId)
    }

    override suspend fun isAuthorizedForAmount(
        accountId: UUID,
        partyId: UUID,
        role: AuthorizationRole,
        amount: com.openbank.libs.domain.money.Money?,
    ): Boolean {
        val account = accountRepository.findById(accountId) ?: return false
        if (account.partyId == partyId) return true
        val active = authorizationRepository.findActiveByAccountAndParty(accountId, partyId)
        if (active.any { it.role == role || it.role == AuthorizationRole.FULL_ACCESS }) return true
        if (amount == null) return hasDelegatedAccess(accountId, partyId, role, account.partyId)
        val now = OffsetDateTime.now(clock)
        return delegationProjectionRepository.findActiveByAccountAndParty(accountId, partyId)
            .filter { it.issuedBy(account.partyId) && it.isActiveOn(now) && it.satisfies(role) }
            .any { it.withinPerTransactionLimit(amount.amount, amount.currency.code) }
    }

    /**
     * The payment path's guard, answered with evidence (ADR-0232 D3/D5, #2990 AC9/AC10).
     *
     * Same three disjuncts and the same order as [isAuthorizedForAmount] — owner, legacy
     * `account_authorizations` row, delegation grant — with two DELIBERATE divergences, both
     * narrowing and both safe because [isAuthorizedForAmount] has no callers on any money path:
     *
     *  1. **The legacy row's own `transactionLimit` is enforced here.** [isAuthorizedForAmount]
     *     ignores it: it returns true on a role match and only ever compares an amount against a
     *     *delegation* grant's ceiling. That was harmless while nothing called it. Wiring this
     *     method to the payment path would otherwise have shipped a live debit route on which an
     *     operator-set per-transaction limit is decoration — a PAYMENT_ONLY row capped at 1 000 CZK
     *     would authorise 1 000 000. Honouring it is not a behaviour change to anything that runs
     *     today; it is a refusal to create the hole.
     *  2. **A refusal is classified.** NO_GRANT and LIMIT_EXCEEDED are different facts about the
     *     grantor's account and belong differently in their transparency view. The caller must
     *     still render both as the same opaque 403 — the classification is for the audit record,
     *     not for the wire.
     *
     * The role asked is always PAYMENT_ONLY: this method exists for debits. FULL_ACCESS satisfies
     * it through [DelegatedAccessGrant.satisfies] / the legacy role check, exactly as before.
     */
    override suspend fun authorizeDelegatedPayment(
        accountId: UUID,
        partyId: UUID,
        amount: com.openbank.libs.domain.money.Money?,
    ): DelegatedPaymentDecision {
        val account = accountRepository.findById(accountId)
            ?: return DelegatedPaymentDecision(DelegatedPaymentOutcome.ACCOUNT_NOT_FOUND)
        if (account.partyId == partyId) return DelegatedPaymentDecision(DelegatedPaymentOutcome.OWNER)

        val role = AuthorizationRole.PAYMENT_ONLY
        val legacy = authorizationRepository.findActiveByAccountAndParty(accountId, partyId)
            .filter { it.role == role || it.role == AuthorizationRole.FULL_ACCESS }
        val legacyPermits = legacy.any { withinLegacyTransactionLimit(it, amount) }

        val now = OffsetDateTime.now(clock)
        val candidates = delegationProjectionRepository.findActiveByAccountAndParty(accountId, partyId)
            .filter { it.issuedBy(account.partyId) && it.isActiveOn(now) && it.satisfies(role) }
        val permitting = candidates.firstOrNull { grant ->
            amount == null || grant.withinPerTransactionLimit(amount.amount, amount.currency.code)
        }

        recordStoreDisagreement(legacyPermits, permitting != null)

        // Order preserved deliberately: the legacy store still answers first, exactly as before.
        // The projection is now evaluated BEFORE this return rather than after it — that is the
        // whole change, and it is why a disagreement can be seen at all. The verdict is not
        // affected: an observation that altered the decision would be a behaviour change smuggled
        // in as telemetry.
        if (legacyPermits) {
            return DelegatedPaymentDecision(DelegatedPaymentOutcome.LEGACY_AUTHORIZATION)
        }

        return when {
            permitting != null -> DelegatedPaymentDecision(
                outcome = DelegatedPaymentOutcome.DELEGATED,
                delegationId = permitting.id,
                grantorPartyId = permitting.grantorPartyId,
            )
            // A candidate existed and every one refused the amount — the grantor's ceiling bit,
            // which is a materially different event from "this party has nothing here".
            candidates.isNotEmpty() || legacy.isNotEmpty() ->
                DelegatedPaymentDecision(DelegatedPaymentOutcome.LIMIT_EXCEEDED)
            else -> DelegatedPaymentDecision(DelegatedPaymentOutcome.NO_GRANT)
        }
    }

    /**
     * The dual-run's actual risk, made visible (ADR-0232 D1, issue #2993).
     *
     * `account_authorizations` and the delegation projection are two stores answering one
     * question, and nothing writes both: `AuthorizationResource.grant`/`.revoke` touch only the
     * former, `DelegationEventConsumer` only the latter. So a revocation in either store leaves
     * the other one granting — and because this method ORs them, the surviving arm carries the
     * debit and no signal anywhere disagrees. That asymmetry is *deliberate* today (the KDoc on
     * [hasDelegatedAccess] chose additive semantics), which is exactly why it needs measuring
     * rather than fixing on a hunch: an "accepted" divergence with no instrument is
     * indistinguishable from an unnoticed one.
     *
     * Recorded only when the two stores genuinely differ, and only for a non-owner — an owner
     * never reaches here, so the counter counts delegation decisions and not traffic. It is
     * emitted at the decision, not by a nightly diff, so it reports the state the guard acted on
     * rather than the state some later snapshot found.
     *
     * A zero on this counter means one of two very different things, and the alert must not
     * confuse them: no divergence, or no delegated payments at all. Both `account_authorizations`
     * and `account_delegation_projection` are empty in the sandbox today, so a zero currently
     * measures traffic. Pair it with the decision count before reading it as an invariant.
     */
    private fun recordStoreDisagreement(legacyPermits: Boolean, delegationPermits: Boolean) {
        if (legacyPermits == delegationPermits) return
        val direction = if (legacyPermits) "legacy_only" else "delegation_only"
        metrics.authorizationStoreDisagreement("account_delegated_payment", direction)
        log.warnf(
            "delegation store disagreement on a payment decision (%s): " +
                "account_authorizations permits=%s, delegation projection permits=%s — " +
                "one store has a grant the other does not (ADR-0232 D1 dual-run, #2993)",
            direction,
            legacyPermits,
            delegationPermits,
        )
    }

    /**
     * A legacy authorization's per-transaction ceiling. A null limit means "no ceiling" (the
     * column is nullable and most rows carry none); a currency mismatch is a refusal rather than
     * a pass, mirroring [DelegatedAccessGrant.withinPerTransactionLimit] — the two ceilings must
     * not disagree about what an unset currency means.
     */
    private fun withinLegacyTransactionLimit(
        auth: AccountAuthorization,
        amount: com.openbank.libs.domain.money.Money?,
    ): Boolean {
        val limit = auth.transactionLimit ?: return true
        if (amount == null) return true
        if (limit.currency != amount.currency) return false
        return amount.amount <= limit.amount
    }

    /**
     * ADR-0232 D3: the third disjunct of the guard — an ACTIVE, in-window delegation
     * grant from the local event-fed projection. Runs after ownership and the legacy
     * AccountAuthorization table so the 99% path (owner) and the existing grants keep
     * their exact behavior; delegation only ever ADDS access, never removes it.
     *
     * [ownerPartyId] is the account's own owner, and a grant only counts when the party who
     * ISSUED it is that owner. Without this the disjunct made a grant row authority in itself:
     * the projection matched on (accountId, granteePartyId) alone, so a grant naming somebody
     * else's account was enforced against that account — two colluding parties could mint
     * payment rights over a stranger's money using nothing but their own valid SCA. The
     * offer-time ownership gate in delegation-service closes the same hole from the other end;
     * this check is the one that re-evaluates on every request instead of trusting a verdict
     * reached once, and it is the last line before the money path.
     */
    private suspend fun hasDelegatedAccess(
        accountId: UUID,
        partyId: UUID,
        role: AuthorizationRole,
        ownerPartyId: UUID,
    ): Boolean {
        val now = OffsetDateTime.now(clock)
        return delegationProjectionRepository.findActiveByAccountAndParty(accountId, partyId)
            .any { it.issuedBy(ownerPartyId) && it.isActiveOn(now) && it.satisfies(role) }
    }
}
