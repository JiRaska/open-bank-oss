// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.*
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.BalanceView
import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.domain.event.AccountClosedEvent
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.PocketResolution
import com.openbank.account.domain.model.PocketRouter
import com.openbank.account.domain.model.PocketStatus
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AccountService(
    private val accountRepository: AccountRepository,
    private val balancePort: BalanceQueryPort,
    private val eventPublisher: AccountEventPublisher,
    private val ibanGenerator: IbanGenerator,
    private val pocketRepository: CurrencyPocketRepository,
    private val sanctionsScreening: AccountSanctionsScreeningPort,
    private val metrics: DomainMetrics,
    private val clock: Clock,
) : AccountUseCase {

    override suspend fun openAccount(command: OpenAccountCommand): Account {
        // ADR-0032 §C: Sanctions gate — fails closed.
        // HIT: confirmed match — hard block.
        // REVIEW: also blocked (Sprint 1 conservative). Sprint 2 will introduce a
        //         PENDING_COMPLIANCE account status so a flagged account can be
        //         released once compliance clears the match manually.
        // CLEAR: proceeds.
        // Unavailable: AccountScreeningUnavailableException propagates — account NOT opened.
        // legalName is non-null at compile time (OpenAccountCommand.legalName: String) —
        // no runtime require needed. The type system enforces ADR-0032 §C statically.
        val screeningKey = "account-open:${command.partyId}:${command.idempotencyKey}"
        val screening = sanctionsScreening.screen(
            name = command.legalName,
            idempotencyKey = screeningKey,
        )
        if (screening.status == "HIT" || screening.status == "REVIEW") {
            throw AccountOpeningBlockedByScreeningException(command.partyId, screening.matchedName)
        }

        val iban = ibanGenerator.generate(command.currency)

        check(!accountRepository.existsByIban(iban)) {
            "IBAN collision — retry"
        }

        val now = Instant.now(clock)
        val account = Account(
            id = UUID.randomUUID(),
            accountNumber = iban,
            accountType = command.accountType,
            partyId = command.partyId,
            productId = command.productId,
            currency = command.currency,
            status = command.initialStatus,
            openedAt = now,
            closedAt = null,
            version = 0L,
            sanctionsScreenedAt = now,
            sanctionsStatus = screening.status,
            legalName = command.legalName.ifBlank { null },
        )

        val saved = accountRepository.save(account)

        // The single-IBAN account opens with one primary pocket in its own currency (ADR-0024).
        pocketRepository.save(
            CurrencyPocket(
                id = UUID.randomUUID(),
                accountId = saved.id,
                currency = saved.currency,
                isPrimary = true,
                status = PocketStatus.ACTIVE,
                openedAt = Instant.now(clock),
                closedAt = null,
                version = 0L,
            ),
        )

        // Operational money lives in the balance-service (N3 / ADR-0024). Balance init is
        // event-driven (ADR-0073): balance-service's BalanceInitConsumer creates the zero
        // balance from the AccountCreated event below. The previous synchronous REST init
        // failed for onboarding accounts opened from a Kafka consumer (no request JWT to
        // propagate → fail-closed; blocking REST call on the Vert.x event loop threw) and,
        // because it ran before this publish, also suppressed the AccountCreated event.

        eventPublisher.publish(
            topic = "openbank.accounts.account.created",
            key = saved.id.toString(),
            event = AccountCreatedEvent(
                aggregateId = saved.id,
                version = saved.version,
                accountNumber = saved.accountNumber.value,
                accountType = saved.accountType,
                partyId = saved.partyId,
                productId = saved.productId,
                currency = saved.currency.code,
                occurredAt = clock.instant(),
            ),
        )

        metrics.accountCreated(saved.accountType.name, saved.currency.code)
        return saved
    }

    override suspend fun closeAccount(command: CloseAccountCommand): Account {
        val account = requireAccount(command.accountId)
        val closed = account.close(clock)
        val updated = accountRepository.update(closed)

        eventPublisher.publish(
            topic = "openbank.accounts.account.status-changed",
            key = updated.id.toString(),
            event = AccountClosedEvent(
                aggregateId = updated.id,
                version = updated.version,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )

        metrics.accountClosed(account.accountType.name, closeReasonTag(command.reason))
        return updated
    }

    override suspend fun activateAccount(accountId: UUID): Account {
        val account = requireAccount(accountId)
        if (account.status == AccountStatus.ACTIVE) return account // idempotent
        val previous = account.status
        val activated = account.activate()
        val updated = accountRepository.update(activated)

        eventPublisher.publish(
            topic = "openbank.accounts.account.status-changed",
            key = updated.id.toString(),
            event = AccountStatusChangedEvent(
                aggregateId = updated.id,
                version = updated.version,
                previousStatus = previous,
                newStatus = updated.status,
                reason = "KYC + AML cleared (ADR-0073)",
                occurredAt = clock.instant(),
            ),
        )

        return updated
    }

    override suspend fun freezeAccount(command: FreezeAccountCommand): Account {
        val account = requireAccount(command.accountId)
        val previous = account.status
        val frozen = account.freeze()
        val updated = accountRepository.update(frozen)

        eventPublisher.publish(
            topic = "openbank.accounts.account.status-changed",
            key = updated.id.toString(),
            event = AccountStatusChangedEvent(
                aggregateId = updated.id,
                version = updated.version,
                previousStatus = previous,
                newStatus = updated.status,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )

        return updated
    }

    override suspend fun unfreezeAccount(command: UnfreezeAccountCommand): Account {
        val account = requireAccount(command.accountId)
        val previous = account.status
        val unfrozen = account.unfreeze()
        val updated = accountRepository.update(unfrozen)

        eventPublisher.publish(
            topic = "openbank.accounts.account.status-changed",
            key = updated.id.toString(),
            event = AccountStatusChangedEvent(
                aggregateId = updated.id,
                version = updated.version,
                previousStatus = previous,
                newStatus = updated.status,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )

        return updated
    }

    override suspend fun getAccount(query: GetAccountQuery): Account = requireAccount(query.accountId)

    override suspend fun getAccountByIban(query: GetAccountByIbanQuery): Account {
        val iban = Iban.of(query.iban)
        return accountRepository.findByIban(iban)
            ?: throw AccountNotFoundException("Account not found for IBAN: ${query.iban}")
    }

    override suspend fun listAccounts(query: ListAccountsQuery): CursorPage<Account> {
        val afterId = query.afterCursor?.let { UUID.fromString(CursorEncoder.decode(it)) }
        val accounts = accountRepository.findByPartyId(query.partyId, query.limit + 1, afterId)
        val hasNext = accounts.size > query.limit
        val page = if (hasNext) accounts.dropLast(1) else accounts
        val nextCursor = if (hasNext) CursorEncoder.encode(page.last().id.toString()) else null
        return CursorPage(
            data = page,
            pagination = PageInfo(limit = query.limit, hasNextPage = hasNext, nextCursor = nextCursor),
        )
    }

    override suspend fun searchAccounts(query: SearchAccountsQuery): CursorPage<Account> {
        // Normalize like an IBAN is normalized at the boundary: strip the print-grouping
        // spaces and upper-case. Below the minimum fragment length the search would degrade
        // to a near-full scan and leak too broad an enumeration window, so we return an empty
        // page instead of querying. The page size is capped to bound the enumeration surface.
        val fragment = query.query.replace(Regex("\\s+"), "").uppercase()
        val limit = query.limit.coerceIn(1, MAX_SEARCH_LIMIT)
        if (fragment.length < MIN_SEARCH_FRAGMENT) {
            return CursorPage(data = emptyList(), pagination = PageInfo(limit = limit, hasNextPage = false))
        }
        val afterId = query.afterCursor?.let { UUID.fromString(CursorEncoder.decode(it)) }
        val accounts = accountRepository.searchByIban(fragment, limit + 1, afterId)
        val hasNext = accounts.size > limit
        val page = if (hasNext) accounts.dropLast(1) else accounts
        val nextCursor = if (hasNext) CursorEncoder.encode(page.last().id.toString()) else null
        return CursorPage(
            data = page,
            pagination = PageInfo(limit = limit, hasNextPage = hasNext, nextCursor = nextCursor),
        )
    }

    override suspend fun getBalance(accountId: UUID): BalanceView {
        val account = requireAccount(accountId)
        return balancePort.getByAccountAndCurrency(accountId, account.currency.code)
            ?: throw AccountNotFoundException("Balance not found for account: $accountId")
    }

    override suspend fun addPocket(command: AddPocketCommand): CurrencyPocket {
        val account = requireAccount(command.accountId)
        check(account.status == AccountStatus.ACTIVE) {
            "Cannot add a pocket to an account in status ${account.status}"
        }
        val existing = pocketRepository.findByAccountIdAndCurrency(command.accountId, command.currency.code)
        require(existing == null) { "Pocket ${command.currency.code} already exists on account ${command.accountId}" }

        val pocket = CurrencyPocket(
            id = UUID.randomUUID(),
            accountId = command.accountId,
            currency = command.currency,
            isPrimary = false,
            status = PocketStatus.ACTIVE,
            openedAt = Instant.now(clock),
            closedAt = null,
            version = 0L,
        )
        val saved = pocketRepository.save(pocket)
        balancePort.initialize(command.accountId, command.currency.code, BigDecimal.ZERO)
        return saved
    }

    override suspend fun closePocket(command: ClosePocketCommand): CurrencyPocket {
        requireAccount(command.accountId)
        val pocket = pocketRepository.findByAccountIdAndCurrency(command.accountId, command.currency.code)
            ?: throw AccountNotFoundException(
                "Pocket ${command.currency.code} not found on account ${command.accountId}",
            )
        return pocketRepository.update(pocket.close(clock))
    }

    override suspend fun listPockets(query: ListPocketsQuery): List<CurrencyPocket> {
        requireAccount(query.accountId)
        return pocketRepository.findByAccountId(query.accountId)
    }

    override suspend fun resolvePocket(query: ResolvePocketQuery): PocketResolution {
        val account = requireAccount(query.accountId)
        val pockets = pocketRepository.findByAccountId(query.accountId)
        return PocketRouter.resolve(
            pockets = pockets,
            paymentCurrency = query.paymentCurrency,
            primaryCurrency = account.currency,
            policy = query.policy,
        )
    }

    // ADR-0153: set/replace or clear the account's optional savings goal. Plain field
    // updates on the existing aggregate (see the ADR's "not a new service" decision) —
    // no new event, no new Kafka topic; the goal is customer-preference metadata, not a
    // money-path state transition.
    override suspend fun updateSavingsGoal(command: UpdateSavingsGoalCommand): Account {
        require(command.name.isNotBlank()) { "Goal name must not be blank" }
        require(command.name.length <= GOAL_NAME_MAX_LENGTH) {
            "Goal name must be at most $GOAL_NAME_MAX_LENGTH characters"
        }
        require(command.targetMinorUnits > 0) { "Goal target must be positive" }
        val account = requireAccount(command.accountId)
        return accountRepository.update(
            account.copy(
                goalName = command.name,
                goalTargetMinorUnits = command.targetMinorUnits,
                goalTargetDate = command.targetDate,
            ),
        )
    }

    override suspend fun clearSavingsGoal(command: ClearSavingsGoalCommand): Account {
        val account = requireAccount(command.accountId)
        return accountRepository.update(
            account.copy(goalName = null, goalTargetMinorUnits = null, goalTargetDate = null),
        )
    }

    private suspend fun requireAccount(id: UUID): Account = accountRepository.findById(id)
        ?: throw AccountNotFoundException("Account not found: $id")

    companion object {
        /** Matches the goal_name VARCHAR(120) column (ADR-0153, V13) — validated app-side too. */
        const val GOAL_NAME_MAX_LENGTH = 120

        /**
         * Map the free-text close reason to a **closed, low-cardinality** set for the
         * `openbank.accounts.closed` `reason` tag (ADR-0077 cardinality contract) — the raw string
         * is operator-supplied and must never become a metric label. Unknown/blank reasons collapse
         * to `other`/`unspecified` rather than leaking arbitrary text.
         */
        fun closeReasonTag(reason: String?): String {
            val r = reason?.lowercase().orEmpty()
            return when {
                reason.isNullOrBlank() -> "unspecified"
                "fraud" in r -> "fraud"
                "regulat" in r || "sanction" in r || "court" in r -> "regulatory"
                "inactiv" in r || "dormant" in r -> "inactivity"
                "custom" in r || "request" in r || "closure" in r -> "customer_request"
                else -> "other"
            }
        }

        /** Below this fragment length a search degrades to a near-full scan / too-broad enumeration. */
        const val MIN_SEARCH_FRAGMENT = 2

        /** Upper bound on the search page size, to cap the account-enumeration surface. */
        const val MAX_SEARCH_LIMIT = 50
    }
}

class AccountNotFoundException(message: String) : RuntimeException(message)

class AccountOpeningBlockedByScreeningException(partyId: UUID, matchedName: String?) :
    RuntimeException("Account opening blocked by sanctions screening for party $partyId (matched: $matchedName)")
