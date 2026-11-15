// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.AddPocketCommand
import com.openbank.account.application.port.`in`.ClearSavingsGoalCommand
import com.openbank.account.application.port.`in`.CloseAccountCommand
import com.openbank.account.application.port.`in`.ClosePocketCommand
import com.openbank.account.application.port.`in`.FreezeAccountCommand
import com.openbank.account.application.port.`in`.GetAccountByIbanQuery
import com.openbank.account.application.port.`in`.GetAccountQuery
import com.openbank.account.application.port.`in`.ListAccountsQuery
import com.openbank.account.application.port.`in`.ListActiveAccountsQuery
import com.openbank.account.application.port.`in`.ListPocketsQuery
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.`in`.RenameAccountCommand
import com.openbank.account.application.port.`in`.ResolvePocketQuery
import com.openbank.account.application.port.`in`.SearchAccountsQuery
import com.openbank.account.application.port.`in`.UnfreezeAccountCommand
import com.openbank.account.application.port.`in`.UpdateSavingsGoalCommand
import com.openbank.account.application.port.out.AccountEventPublisher
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.BalanceView
import com.openbank.account.application.port.out.CurrencyPocketRepository
import com.openbank.account.application.port.out.NotificationRequestPort
import com.openbank.account.application.port.out.PartyDirectoryPort
import com.openbank.account.application.port.out.PassportCommitment
import com.openbank.account.application.port.out.PassportIntent
import com.openbank.account.application.port.out.PassportOpeningDraft
import com.openbank.account.application.port.out.PassportOpeningJob
import com.openbank.account.application.port.out.PassportOperation
import com.openbank.account.application.port.out.SanctionsEntityType
import com.openbank.account.application.port.out.SanctionsScreenResult
import com.openbank.account.domain.event.AccountClosedEvent
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.event.AccountProductRevision
import com.openbank.account.domain.event.AccountStatusChangedEvent
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.CurrencyPocket
import com.openbank.account.domain.model.PocketResolution
import com.openbank.account.domain.model.PocketRouter
import com.openbank.account.domain.model.PocketStatus
import com.openbank.account.domain.model.ProductRevisionPin
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.audit.AuditChain
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import com.openbank.libs.observability.DomainMetrics
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("LongParameterList")
class AccountService(
    private val accountRepository: AccountRepository,
    private val balancePort: BalanceQueryPort,
    private val eventPublisher: AccountEventPublisher,
    private val ibanGenerator: IbanGenerator,
    private val pocketRepository: CurrencyPocketRepository,
    private val sanctionsScreening: AccountSanctionsScreeningPort,
    private val productRevisionPinning: ProductRevisionPinning,
    private val metrics: DomainMetrics,
    private val clock: Clock,
    /**
     * Customer-facing lifecycle notifications (#8432). NO Kotlin default value: a default on a
     * CDI bean's constructor parameter makes Arc fail to resolve the bean, silently.
     */
    private val notificationRequestPort: NotificationRequestPort,
    private val passport: PassportCommitment,
    private val parties: PartyDirectoryPort,
) : AccountUseCase {

    private suspend fun screenForOpening(command: OpenAccountCommand): SanctionsScreenResult {
        // ADR-0032 §C: Sanctions gate — fails closed.
        // Only CLEAR and WHITELISTED proceed (SanctionsScreenResult.permitsOpening). HIT,
        // POTENTIAL_HIT, ESCALATED and a response with no status all block; a flagged party
        // needs compliance to clear the match (WHITELISTED) before an account can open.
        // Unavailable: AccountScreeningUnavailableException propagates — account NOT opened.
        // legalName is non-null at compile time (OpenAccountCommand.legalName: String) —
        // no runtime require needed. The type system enforces ADR-0032 §C statically.
        val screeningKey = "account-open:${command.partyId}:${command.idempotencyKey}"
        val screening = sanctionsScreening.screen(
            name = command.legalName,
            idempotencyKey = screeningKey,
            subjectId = command.partyId.toString(),
        )
        if (!screening.permitsOpening) {
            throw AccountOpeningBlockedByScreeningException(command.partyId, screening.matchedName)
        }
        return screening
    }

    override suspend fun openAccount(command: OpenAccountCommand): Account {
        // Idempotent replay (#465): a repeated key returns the original account and never opens
        // a second one. The Redis record in the REST layer is only a response cache — this DB
        // check (and the transactional key insert in saveNewAccount) is the source of truth.
        accountRepository.findByIdempotencyKey(command.idempotencyKey)?.let {
            return replayOrRefuse(it, command.idempotencyKey, command.requestHash)
        }

        val screening = screenForOpening(command)

        // ADR-0341 D1 / ADR-0352 phase 3: the account pins the PUBLISHED catalog revision it opens
        // under, taken at the same instant as openedAt. Fail-closed on the pin: no product, an
        // ineligible product, no published revision or an unreachable catalog each refuse the
        // opening before anything is written (issue #668 replaced; it used to proceed on an outage).
        val pending = passport.find(openingKey(command.idempotencyKey))
        pending?.requireSameOpening(command)
        val pin = pending?.pin ?: freshOpeningPin(command)
        val passportOperation = pending ?: preparePassportOpening(command, pin)

        // #9044: a TERM_DEPOSIT must never open without a record of the terms version it was
        // opened under — the terms carry the early-withdrawal penalty and the notice period, and
        // "which terms govern this deposit" is the first question of any complaints/conduct
        // review. IllegalArgumentException → 400 via libs-runtime's mapper, same as the REST
        // layer's own validation. The DB backs this with chk_accounts_terms (NOT VALID — honest
        // nulls on pre-V24 accounts stay untouched).
        require(command.accountType != AccountType.TERM_DEPOSIT || !command.termsVersion.isNullOrBlank()) {
            "termsVersion is required when opening a TERM_DEPOSIT account"
        }

        val iban = ibanGenerator.generate(command.currency)

        check(!accountRepository.existsByIban(iban)) {
            "IBAN collision — retry"
        }

        val account = Account(
            id = passportOperation?.accountId ?: Ids.newId(),
            accountNumber = iban,
            accountType = command.accountType,
            partyId = command.partyId,
            productId = command.productId,
            currency = command.currency,
            status = if (passportOperation != null) AccountStatus.PENDING_ACTIVATION else command.initialStatus,
            openedAt = (passportOperation?.pin ?: pin).pinnedAt,
            closedAt = null,
            version = 0L,
            sanctionsScreenedAt = clock.instant(),
            sanctionsStatus = screening.status,
            legalName = command.legalName.ifBlank { null },
            termsVersion = command.termsVersion,
            termsUrl = command.termsUrl,
            termsEffectiveFrom = command.termsEffectiveFrom,
            productRevisionPin = passportOperation?.pin ?: pin,
        )

        if (passportOperation != null) {
            passport.stageOpening(passportOperation, account.toPassportDraft())
            passport.admit(passportOperation)
        }
        return commitOpening(account, command.idempotencyKey, command.requestHash, passportOperation)
    }

    internal suspend fun recoverPassportOpening(job: PassportOpeningJob) {
        val (intent, draft) = job
        SyntheticTaintKafkaRail.withTaint(draft.synthetic) {
            val account = job.toAccount()
            val pending = if (passport.hasReceipt(intent)) account else admitRecoveredOpening(intent, account)
            if (pending != null) {
                commitOpening(pending, intent.key.removePrefix("OPEN:"), intent.binding, intent)
            }
        }
    }

    private suspend fun admitRecoveredOpening(intent: PassportIntent, account: Account): Account? {
        val party = parties.find(intent.partyId) ?: return null
        if (party.status !in RECOVERABLE_PARTY_STATUSES || party.legalName.isBlank()) return null
        val entityType = when (party.partyType) {
            "INDIVIDUAL", "SOLE_TRADER" -> SanctionsEntityType.INDIVIDUAL
            "COMPANY", "TRUST" -> SanctionsEntityType.ORGANIZATION
            else -> return null
        }
        val screening = sanctionsScreening.screen(
            name = party.legalName,
            idempotencyKey = "passport-recovery:${Ids.randomId()}",
            subjectId = intent.partyId.toString(),
            entityType = entityType,
        )
        if (!screening.permitsOpening) return null
        val screenedAt = clock.instant()
        passport.admit(intent)
        return account.copy(sanctionsScreenedAt = screenedAt, sanctionsStatus = screening.status)
    }

    private suspend fun commitOpening(
        account: Account,
        key: String,
        binding: String?,
        intent: PassportIntent?,
    ): Account {
        // Account, pocket, idempotency key, outbox and queue cleanup share the account transaction.
        val saved = try {
            accountRepository.saveNewAccount(account, primaryPocketFor(account), key, binding, ::accountCreatedEvent)
        } catch (e: PersistenceException) {
            return resolveOpeningRace(e, key, binding, intent)
        } catch (e: PgException) {
            return resolveOpeningRace(e, key, binding, intent)
        }

        metrics.accountCreated(saved.accountType.name, saved.currency.code)
        if (saved.status == AccountStatus.ACTIVE) {
            notifyCustomer("account opened") {
                notificationRequestPort.notifyAccountOpened(saved.partyId, saved.accountNumber.value)
            }
        }
        return saved
    }

    private suspend fun resolveOpeningRace(
        failure: RuntimeException,
        key: String,
        binding: String?,
        intent: PassportIntent?,
    ): Account {
        if (intent == null) return replayOrRefuse(recoverConcurrentReplay(failure, key), key, binding)
        val winner = accountRepository.findByIdempotencyKey(key) ?: throw failure
        if (winner.id != intent.accountId) throw failure
        return replayOrRefuse(winner, key, binding)
    }

    /** The event the opening transaction writes to the outbox, built from the row as persisted. */
    private fun accountCreatedEvent(saved: Account) = AccountCreatedEvent(
        aggregateId = saved.id,
        version = saved.version,
        accountNumber = saved.accountNumber.value,
        accountType = saved.accountType,
        partyId = saved.partyId,
        productId = saved.productId,
        currency = saved.currency.code,
        occurredAt = saved.openedAt,
        sourceService = "account-service",
        productRevision = saved.productRevisionPin?.let(AccountProductRevision::of),
    )

    override suspend fun closeAccount(command: CloseAccountCommand): Account {
        val account = requireAccount(command.accountId)
        // Status validity first (throws IllegalStateException for an already-CLOSED/PENDING
        // account) so a doomed request fails on "wrong state" rather than a balance lookup it
        // was never going to need.
        val closed = account.close(clock)
        // KNOWN LIMITATIONS (flagged for review, not silently swept under the guard):
        //  1. This is a point-in-time balance read, not a lock — a credit that settles between
        //     this check and accountRepository.update() below still lands on the now-CLOSED
        //     account. account-service's own optimistic version check (AccountUpdateConflict-
        //     Exception) does not cover this: the race is against balance-service state, not
        //     this row's version.
        //  2. It only sees CURRENT booked/reserved balance, not money already in flight to this
        //     account — a future-dated standing order or an internal transfer sitting on its
        //     documented value-date delay reads as zero here today and still executes later
        //     against a closed account. Catching that needs a cross-service check (standing-
        //     order-service, sepa-instant, etc.) that does not exist yet — out of scope for this
        //     guard; closing this gap is a follow-up, not something this check can fix alone.
        val balances = balancePort.getByAccount(command.accountId)
        val notEmpty = balances.filter { it.booked.signum() != 0 || it.reserved.signum() != 0 }
        if (notEmpty.isNotEmpty()) {
            throw AccountNotEmptyException(
                "Account ${command.accountId} still holds money in " +
                    notEmpty.joinToString(", ") { "${it.currency} ${it.booked}" } +
                    " — move it out before closing",
            )
        }
        val updated = accountRepository.update(closed)

        eventPublisher.publish(
            topic = "openbank.accounts.account.status-changed",
            key = updated.id.toString(),
            event = AccountClosedEvent(
                aggregateId = updated.id,
                version = updated.version,
                reason = command.reason,
                occurredAt = clock.instant(),
                sourceService = "account-service",
            ),
        )

        metrics.accountClosed(account.accountType.name, closeReasonTag(command.reason))
        notifyCustomer("account closed") {
            notificationRequestPort.notifyAccountClosed(updated.partyId, updated.accountNumber.value)
        }
        return updated
    }

    override suspend fun activateAccount(accountId: UUID): Account {
        val account = requireAccount(accountId)
        if (account.status == AccountStatus.ACTIVE) return account // idempotent
        val previous = account.status
        val activated = account.activate()
        val passportOpening = passport.openingFor(account.id)
        if (passportOpening != null) {
            admitPassportActivation(account, passportOpening)
        } else {
            account.productRevisionPin?.let {
                productRevisionPinning.checkActivation(account.productId, it, clock.instant())
            }
        }
        val updated = if (passportOpening != null) {
            accountRepository.update(activated) { activationEvent(it, previous) }
        } else {
            accountRepository.update(activated).also {
                eventPublisher.publish(
                    topic = "openbank.accounts.account.status-changed",
                    key = it.id.toString(),
                    event = activationEvent(it, previous),
                )
            }
        }

        // Idempotent above (an already-ACTIVE account returns early), so this fires once —
        // and it is the moment the customer can actually use the account.
        notifyCustomer("account activated") {
            notificationRequestPort.notifyAccountOpened(updated.partyId, updated.accountNumber.value)
        }
        return updated
    }

    private fun activationEvent(account: Account, previous: AccountStatus) = AccountStatusChangedEvent(
        aggregateId = account.id,
        version = account.version,
        previousStatus = previous,
        newStatus = account.status,
        reason = "KYC + AML cleared (ADR-0267)",
        occurredAt = clock.instant(),
        sourceService = "account-service",
    )

    private suspend fun freshOpeningPin(command: OpenAccountCommand): ProductRevisionPin =
        productRevisionPinning.pinFor(
            command.productId,
            command.currency.code,
            clock.instant(),
            passportAdmissionRequired = command.passportGrantId != null,
        )

    private suspend fun preparePassportOpening(command: OpenAccountCommand, pin: ProductRevisionPin): PassportIntent? {
        val grant = command.passportGrantId ?: return null
        return passport.prepare(
            PassportIntent(
                openingKey(command.idempotencyKey),
                Ids.newId(),
                Ids.newId(),
                grant,
                command.partyId,
                PassportOperation.OPEN,
                requireNotNull(command.requestHash) { "passport opening requires a request fingerprint" },
                pin,
            ),
        ).also { it.requireSameOpening(command) }
    }

    private fun openingKey(idempotencyKey: String): String = "OPEN:$idempotencyKey"

    private fun PassportIntent.requireSameOpening(command: OpenAccountCommand) {
        if (binding != command.requestHash || partyId != command.partyId || grantId != command.passportGrantId) {
            throw IdempotencyKeyReusedException()
        }
    }

    private suspend fun admitPassportActivation(account: Account, opening: PassportIntent) {
        val pin = checkNotNull(account.productRevisionPin) { "passport account requires a product revision" }
        val activationBinding = AuditChain.sha256Hex("${account.id}:${account.version}:${pin.revisionId}")
        val intent = passport.prepare(
            PassportIntent(
                "ACTIVATE:${account.id}",
                Ids.newId(),
                account.id,
                opening.grantId,
                account.partyId,
                PassportOperation.ACTIVATE,
                activationBinding,
                pin,
            ),
        )
        if (intent.binding != activationBinding || intent.partyId != account.partyId) {
            throw ResourceConflictException("passport activation operation no longer matches the account")
        }
        passport.admit(intent)
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
                sourceService = "account-service",
            ),
        )

        notifyCustomer("account frozen") {
            notificationRequestPort.notifyAccountFrozen(
                updated.partyId,
                updated.accountNumber.value,
                command.reason,
            )
        }
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
                sourceService = "account-service",
            ),
        )

        return updated
    }

    /**
     * Emit a customer notification after the state change is persisted, never instead of it.
     *
     * **The failure is swallowed on purpose.** Opening, closing, freezing and activating an account
     * are money-path state changes with events and audit behind them; a broker hiccup must not roll
     * one back, nor turn a completed operation into a 500 that an operator or a saga retries. So a
     * customer may, rarely, not be told about something that did happen — the honest direction to
     * fail. Logged at WARN rather than dropped, because "the customer was not told" is an
     * operational fact someone can act on.
     */
    private suspend fun notifyCustomer(what: String, emit: suspend () -> Unit) {
        runCatching { emit() }.onFailure { failure ->
            LOG.warnf(failure, "%s recorded but the customer notification could not be published (#8432)", what)
        }
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

    override suspend fun listActiveAccounts(query: ListActiveAccountsQuery): CursorPage<Account> {
        // A fleet-wide sweep read (ADR-0143 billing discovery) — larger pages than the
        // customer-facing list are practical here, but the size is still capped so a single
        // request cannot dump the whole book.
        val limit = query.limit.coerceIn(1, MAX_ACTIVE_LIST_LIMIT)
        val afterId = query.afterCursor?.let { UUID.fromString(CursorEncoder.decode(it)) }
        val accounts = accountRepository.findActive(limit + 1, afterId)
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

    // Same "plain field update on the existing aggregate" shape as the savings goal above —
    // the nickname is cosmetic customer-preference metadata, not a money-path transition, so
    // no new event/topic either.
    override suspend fun renameAccount(command: RenameAccountCommand): Account {
        require(command.nickname == null || command.nickname.length <= NICKNAME_MAX_LENGTH) {
            "Nickname must be at most $NICKNAME_MAX_LENGTH characters"
        }
        val account = requireAccount(command.accountId)
        return accountRepository.update(account.rename(command.nickname))
    }

    private suspend fun requireAccount(id: UUID): Account = accountRepository.findById(id)
        ?: throw AccountNotFoundException("Account not found: $id")

    /** The single-IBAN account opens with one primary pocket in its own currency (ADR-0024). */
    private fun primaryPocketFor(account: Account): CurrencyPocket = CurrencyPocket(
        id = Ids.newId(),
        accountId = account.id,
        currency = account.currency,
        isPrimary = true,
        status = PocketStatus.ACTIVE,
        openedAt = Instant.now(clock),
        closedAt = null,
        version = 0L,
    )

    /**
     * #10916: the durable half of the Idempotency-Key check. The Redis record expires (or is
     * evicted) while the account_idempotency row does not, so a key reused for a DIFFERENT
     * opening must be refused here too rather than answered with the first account. A key stored
     * without a fingerprint (legacy row, or a caller with none) keeps the plain replay.
     */
    private suspend fun replayOrRefuse(existing: Account, key: String, requestHash: String?): Account {
        requestHash ?: return existing
        val stored = accountRepository.findIdempotencyRequestHash(key)
        if (stored != null && stored != requestHash) throw IdempotencyKeyReusedException()
        return existing
    }

    /**
     * The loser of a concurrent duplicate-open race: both contenders passed the replay check
     * before either committed, and this transaction died on the account_idempotency primary
     * key. Recover by returning the winner's account. Anything that is not the idempotency-key
     * conflict propagates untouched.
     */
    private suspend fun recoverConcurrentReplay(e: RuntimeException, idempotencyKey: String): Account {
        val isIdempotencyKeyConflict = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
            .any { it.message?.contains("account_idempotency", ignoreCase = true) == true }
        if (!isIdempotencyKeyConflict) throw e
        return accountRepository.findByIdempotencyKey(idempotencyKey) ?: throw e
    }

    companion object {
        private val RECOVERABLE_PARTY_STATUSES = setOf("PENDING_KYC", "ACTIVE")
        private val LOG: Logger = Logger.getLogger(AccountService::class.java)

        /** Matches the goal_name VARCHAR(120) column (ADR-0153, V13) — validated app-side too. */
        const val GOAL_NAME_MAX_LENGTH = 120

        /** Matches the nickname VARCHAR(60) column (V20) — validated app-side too. */
        const val NICKNAME_MAX_LENGTH = 60

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

        /**
         * Upper bound on the active-account sweep page size (ADR-0143 billing discovery).
         * Larger than [MAX_SEARCH_LIMIT] because the caller is a paging batch job, not an
         * interactive search — but still bounded so one request cannot dump the whole book.
         */
        const val MAX_ACTIVE_LIST_LIMIT = 200
    }
}

// #10911/#11059 phase 3 (money-path): extends the libs-domain base so libs-runtime's
// ResourceNotFoundExceptionMapper handles the 404, keeping the domain-specific "ACCOUNT_NOT_FOUND"
// code the deleted local AccountNotFoundExceptionMapper used — byte-for-byte identical status,
// code and message. See AccountExceptionMapperEquivalenceTest.
class AccountNotFoundException(message: String) : ResourceNotFoundException(message, code = "ACCOUNT_NOT_FOUND")

/**
 * A lifecycle update raced a concurrent modification of the same account (#465): the caller's
 * domain object was read at a version the row no longer has. Dedicated type (not
 * IllegalStateException — that has two competing mappers, libs 422 vs service, picked
 * non-deterministically per request; see issue #526) mapped to 409.
 *
 * #10911/#11059 phase 3: extends the libs-domain base, keeping the domain-specific
 * "CONCURRENT_MODIFICATION" code the deleted local AccountUpdateConflictExceptionMapper used —
 * byte-for-byte identical status, code and message. See AccountExceptionMapperEquivalenceTest.
 */
class AccountUpdateConflictException(message: String, cause: Throwable? = null) :
    ResourceConflictException(message, code = "CONCURRENT_MODIFICATION", cause = cause)

/**
 * Closing an account with money still in any of its currency pockets would strand that money —
 * the account moves to CLOSED and stops appearing anywhere a customer or ops person would think
 * to look for it. [closeAccount] refuses rather than closing anyway; the caller must move the
 * balance out (or open a dispute) first.
 *
 * Best-effort, not a guarantee: see the KNOWN LIMITATIONS note at the [closeAccount] call site
 * for the point-in-time race and the in-flight-money gap this check does not cover.
 */
class AccountNotEmptyException(message: String) : RuntimeException(message)

class AccountOpeningBlockedByScreeningException(partyId: UUID, matchedName: String?) :
    RuntimeException("Account opening blocked by sanctions screening for party $partyId (matched: $matchedName)")

/**
 * Issue #668: account opening refused because product-catalog confirmed the product doesn't
 * exist, or exists but isn't ACTIVE. Never thrown when product-catalog is merely unreachable —
 * that fails open (see [ProductCatalogPort]). Extends [IllegalStateException] deliberately (not
 * a bare [RuntimeException] like [AccountOpeningBlockedByScreeningException]) so it resolves to
 * the libs-runtime `IllegalStateExceptionMapper` (422 BUSINESS_RULE_VIOLATION) instead of falling
 * through to the generic 500 mapper. The screening exception above must NEVER copy this pattern
 * (#8512): its message carries the matched sanctions name, and the IllegalStateException mapper
 * echoes `message` on the wire — a free sanctions-list oracle. It has a dedicated mapper in
 * ExceptionMappers.kt that answers 422 with a fixed body and keeps the detail in a WARN log.
 */
class ProductNotEligibleException(productId: UUID, reason: String) :
    IllegalStateException("Cannot open account against product $productId: $reason")

private fun Account.toPassportDraft() = PassportOpeningDraft(
    iban = accountNumber.value,
    productId = productId,
    accountType = accountType,
    currency = currency.code,
    sanctionsScreenedAt = checkNotNull(sanctionsScreenedAt),
    sanctionsStatus = checkNotNull(sanctionsStatus),
    termsVersion = termsVersion,
    termsUrl = termsUrl,
    termsEffectiveFrom = termsEffectiveFrom,
    synthetic = SyntheticTaintKafkaRail.currentlyTainted(),
)

private fun PassportOpeningJob.toAccount() = Account(
    id = intent.accountId,
    accountNumber = Iban.of(draft.iban),
    accountType = draft.accountType,
    partyId = intent.partyId,
    productId = draft.productId,
    currency = CurrencyCode.of(draft.currency),
    status = AccountStatus.PENDING_ACTIVATION,
    openedAt = intent.pin.pinnedAt,
    closedAt = null,
    version = 0L,
    sanctionsScreenedAt = draft.sanctionsScreenedAt,
    sanctionsStatus = draft.sanctionsStatus,
    termsVersion = draft.termsVersion,
    termsUrl = draft.termsUrl,
    termsEffectiveFrom = draft.termsEffectiveFrom,
    productRevisionPin = intent.pin,
)
