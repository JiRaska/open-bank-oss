// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.`in`.*
import com.openbank.interest.application.port.out.*
import com.openbank.interest.domain.model.*
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTaxPolicy
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
@Suppress("LongParameterList")
class InterestService(
    private val configRepo: InterestRateConfigRepository,
    private val accrualRepo: InterestAccrualRepository,
    private val capitalizationRepo: InterestCapitalizationRepository,
    private val taxProfilePort: TaxProfilePort,
    private val ledgerPostingPort: LedgerPostingPort,
    private val accountDirectoryPort: AccountDirectoryPort,
    @ConfigProperty(name = "openbank.interest.day-count-convention", defaultValue = "ACT_365")
    private val defaultDayCount: String,
    @ConfigProperty(name = "openbank.interest.accruable-account-types", defaultValue = "CURRENT,SAVINGS")
    private val accruableAccountTypes: String,
    private val clock: Clock,
) : AccrueInterestUseCase,
    CapitalizeInterestUseCase,
    GetAccrualsUseCase,
    ManageRateConfigUseCase {

    @Inject
    constructor(
        configRepo: InterestRateConfigRepository,
        accrualRepo: InterestAccrualRepository,
        capitalizationRepo: InterestCapitalizationRepository,
        taxProfilePort: TaxProfilePort,
        ledgerPostingPort: LedgerPostingPort,
        accountDirectoryPort: AccountDirectoryPort,
        @ConfigProperty(name = "openbank.interest.day-count-convention", defaultValue = "ACT_365")
        defaultDayCount: String,
        @ConfigProperty(name = "openbank.interest.accruable-account-types", defaultValue = "CURRENT,SAVINGS")
        accruableAccountTypes: String,
    ) : this(
        configRepo,
        accrualRepo,
        capitalizationRepo,
        taxProfilePort,
        ledgerPostingPort,
        accountDirectoryPort,
        defaultDayCount,
        accruableAccountTypes,
        Clock.systemUTC(),
    )

    private val log = Logger.getLogger(InterestService::class.java)

    override fun accrue(request: AccrualRequest): Uni<InterestAccrual> =
        configRepo.findEffectiveRate(request.accountId, request.productId, request.accrualDate, request.currency)
            .flatMap { config ->
                if (config == null) {
                    // Fails closed: no rate for this (account/product, currency) — see #1265. 422, not 500.
                    Uni.createFrom().failure(
                        RateConfigNotFoundException(request.productId, request.currency),
                    )
                } else {
                    val divisor = when (config.dayCount) {
                        DayCount.ACT_360 -> BigDecimal(360)
                        else -> BigDecimal(365)
                    }
                    val dailyRate = config.annualRate.divide(divisor, 10, RoundingMode.HALF_UP)
                    val accruedAmount = request.balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)
                    val accrual = InterestAccrual(
                        accountId = request.accountId,
                        productId = request.productId,
                        configId = config.id,
                        accrualDate = request.accrualDate,
                        balance = request.balance,
                        dailyRate = dailyRate,
                        accruedAmount = accruedAmount,
                        currency = request.currency,
                        createdAt = OffsetDateTime.now(clock),
                    )
                    accrualRepo.save(accrual)
                }
            }

    /**
     * The daily accrual run: discover every ACTIVE interest-bearing account fleet-wide (paged via
     * account-service's ADR-0143 cursor list), read each one's booked balance, and post one accrual
     * for [date]. Returns the number of accruals actually written.
     *
     * Per-account resilience: a missing rate config, an unavailable balance, or a duplicate (the
     * `UNIQUE(account_id, accrual_date, product_id)` constraint — i.e. this account was already
     * accrued for [date], so the run is safely **idempotent** and re-runnable) collapses that one
     * account to a no-op (0), never aborting the batch. Accounts are processed sequentially
     * (`concatenate`) to keep a bounded, polite load on account-service.
     */
    override fun accrueAll(date: LocalDate): Uni<Int> {
        val accruableTypes = accruableAccountTypes.split(",").map {
            it.trim().uppercase()
        }.filter { it.isNotEmpty() }.toSet()
        return collectAccruableAccounts(cursor = null, acc = emptyList(), accruableTypes = accruableTypes)
            .flatMap { accounts ->
                if (accounts.isEmpty()) {
                    Uni.createFrom().item(0)
                } else {
                    Multi.createFrom().iterable(accounts)
                        .onItem().transformToUniAndConcatenate { account -> accrueOneForDate(account, date) }
                        .collect().with(java.util.stream.Collectors.summingInt { it })
                }
            }
    }

    /** Walk account-service's cursor pages, keeping only the accruable account types, until the
     *  cursor is exhausted. Page count is small (fleet-wide ACTIVE set); recursion depth == pages. */
    private fun collectAccruableAccounts(
        cursor: String?,
        acc: List<AccountSnapshot>,
        accruableTypes: Set<String>,
    ): Uni<List<AccountSnapshot>> = accountDirectoryPort.listActiveAccounts(cursor, ACCRUAL_PAGE_SIZE).flatMap { page ->
        val kept = acc + page.items.filter { it.accountType.uppercase() in accruableTypes }
        if (page.nextCursor == null) {
            Uni.createFrom().item(kept)
        } else {
            collectAccruableAccounts(page.nextCursor, kept, accruableTypes)
        }
    }

    /** Accrue one account for [date]; 1 if written, 0 if skipped (zero/negative balance, no rate
     *  config, unavailable balance, or already accrued for [date]). Never fails the caller. */
    private fun accrueOneForDate(account: AccountSnapshot, date: LocalDate): Uni<Int> =
        accountDirectoryPort.bookedBalance(account.id).flatMap { balance ->
            if (balance == null || balance.booked.signum() <= 0) {
                Uni.createFrom().item(0)
            } else {
                accrue(
                    AccrualRequest(
                        accountId = account.id,
                        productId = account.productId,
                        balance = balance.booked,
                        currency = balance.currency,
                        accrualDate = date,
                    ),
                ).map { 1 }.onFailure().recoverWithItem(0)
            }
        }

    /**
     * Capitalizes one `(account, product)` period (ADR-0033 §B/§D): claim → post to the GL → commit.
     *
     * ### The claim, and why it exists
     *
     * The ledger idempotency key is the capitalization's business identity
     * (`account, product, periodTo`) and carries **no amount** — on purpose: a key that varied with
     * the amount would not collide on a retry, it would post a SECOND journal and double-credit the
     * customer. That makes the key amount-blind, so the accrual set the ledger is told about and the
     * accrual set the capitalization row records MUST be pinned to be the same set. Reading it twice
     * is not enough: `findPendingCapitalization` has no lower date bound, so an accrual backfilled
     * for an earlier date between the two reads silently enlarges the second one. The ledger would
     * then replay the first journal (100) while this service committed the second amount (120), and
     * the withholding remittance would pay real cash on 20 CZK the customer never received.
     *
     * So the set is **claimed** — flipped `ACCRUING → CAPITALIZING` in its own committed transaction
     * — before anything is posted, and only a claimed set is ever credited.
     *
     * ### Crash recovery
     *
     * A crash at any point after the claim leaves the set `CAPITALIZING`. A **plain retry of
     * `capitalize(accountId, productId, toDate)` recovers it**: this method looks for a claimed set
     * first, finds that exact one, re-derives the identical gross/net/tax and therefore the identical
     * idempotency key, and the ledger collapses the replay onto the journal it already booked (or
     * books it now, if the crash preceded the post). Nothing needs an operator, and there is no way
     * to wedge: a claimed set is always completable. An accrual backfilled after the claim is
     * `ACCRUING`, outside it, and falls into the next period — which is the right answer, because it
     * is not part of the credit the ledger booked.
     *
     * The one case that is refused rather than guessed is a claim held for a *different* period end:
     * completing it under this `toDate` would mint a second key and post a second journal. See
     * [inFlightClaimFailure].
     */
    override fun capitalize(accountId: UUID, productId: String, toDate: LocalDate): Uni<InterestCapitalization> =
        accrualRepo.findClaimedForCapitalization(accountId, productId).flatMap { claimed ->
            when {
                claimed.isEmpty() -> claimAndCapitalize(accountId, productId, toDate)
                claimed.all { it.claimedPeriodTo == toDate } ->
                    capitalizeSet(accountId, productId, toDate, claimed, alreadyClaimed = true)
                else -> Uni.createFrom().failure(inFlightClaimFailure(accountId, productId, toDate, claimed))
            }
        }

    /** No claim outstanding: take a fresh `ACCRUING` set and claim it for [toDate]. */
    private fun claimAndCapitalize(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
    ): Uni<InterestCapitalization> =
        accrualRepo.findPendingCapitalization(accountId, productId, toDate).flatMap { accruals ->
            if (accruals.isEmpty()) {
                Uni.createFrom().failure(IllegalStateException("No pending accruals to capitalize"))
            } else {
                capitalizeSet(accountId, productId, toDate, accruals, alreadyClaimed = false)
            }
        }

    /**
     * A `CAPITALIZING` set claimed for another period end. Capitalizing it to [toDate] would derive a
     * different idempotency key from the one the interrupted attempt used, so the ledger would not
     * recognise the replay and would post a SECOND journal — for accruals the first journal may
     * already have credited. There is no safe guess, so refuse and name the retry that fixes it.
     */
    private fun inFlightClaimFailure(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
        claimed: List<InterestAccrual>,
    ): IllegalStateException {
        val periods = claimed.mapNotNull { it.claimedPeriodTo }.distinct().sorted()
        return IllegalStateException(
            "Refusing to capitalize account=$accountId product=$productId to periodTo=$toDate: " +
                "${claimed.size} accrual(s) are already CAPITALIZING, claimed for $periods. Complete that " +
                "capitalization first by retrying capitalize(account, product, ${periods.firstOrNull()}) — " +
                "it is idempotent and will collapse onto the journal the interrupted attempt booked. " +
                "Capitalizing the same accruals to a different period end would post a SECOND journal.",
        )
    }

    /**
     * A pending set spanning several currencies has no single correct capitalization: the accrued
     * numerics are not commensurable (summing 100 CZK and 5 EUR into "105" is nonsense), and
     * [WithholdingTaxPolicy] assesses per currency (§E — only CZK is withheld in v1), so folding
     * them would also withhold against the wrong base. There is no safe guess, so refuse loudly and
     * leave every accrual `ACCRUING`.
     *
     * NOTE (issue #1265): this is now a **defence-in-depth assertion, expected to be unreachable**.
     * Two structural changes closed the path that used to feed it a mixed set: [InterestRateConfig]
     * now carries a `currency` and `accrue` resolves a rate only in the accrual's own currency
     * (an account can no longer accrue in a currency it has no rate for — it fails closed with
     * [RateConfigNotFoundException]), and the `interest_accruals` UNIQUE key includes `currency`
     * (V12), so two same-date rows in different currencies can never collapse into one capitalize
     * set. It is kept — not deleted — because it is the last guard before money is posted, and a
     * future writer for [AccrualStatus.REVERSED] / [AccrualStatus.SUSPENDED], a manual DB edit, or a
     * data migration could still fabricate a mixed set; better to refuse loudly than to sum
     * incommensurable currencies. If it ever fires in practice, that is a real bug upstream, not an
     * operator-actionable state — do not tell a caller to "split the accruals" as if that were a
     * supported path.
     */
    private fun mixedCurrencyFailure(accountId: UUID, productId: String, currencies: List<String>) =
        IllegalStateException(
            "Refusing to capitalize a mixed-currency accrual set for account=$accountId product=$productId: " +
                "pending accruals are denominated in ${currencies.sorted()}. Interest must be capitalized per " +
                "(product, currency), but there is currently no operator or API path to split, reverse, or " +
                "otherwise resolve this set — see issue #1265. Manual intervention is required.",
        )

    /**
     * A negative gross is refused outright, and nothing is claimed, posted or committed.
     *
     * It is reachable: `interest_rate_configs.annual_rate` has no CHECK and `createConfig` does not
     * validate, so a negative rate accrues negative interest. The old code *skipped the ledger* for
     * a non-positive gross, which committed a capitalization row saying the customer had been
     * charged while the GL recorded nothing at all — the exact interest-service-vs-GL divergence
     * this class now exists to prevent. Its KDoc cited V6's `WHERE total_accrued <> 0` as authority,
     * but V6 excludes only ZERO: it treats a negative capitalization as money-bearing and constrains
     * it. A negative credit would be `Dr 2100 / Cr 401x` — a debit of the customer's pocket — which
     * nothing in this service builds and which is a product decision, not a rounding detail.
     */
    private fun negativeGrossFailure(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
        gross: BigDecimal,
        currency: String,
    ) = IllegalStateException(
        "Refusing to capitalize a NEGATIVE gross for account=$accountId product=$productId " +
            "periodTo=$toDate: gross=$gross $currency. Charging a customer via the interest " +
            "capitalization path is not modelled (it would reverse the ADR-0033 §D split to " +
            "Dr deposit-control / Cr interest-expense); nothing is credited, nothing is withheld " +
            "and the accruals stay ACCRUING. Check interest_rate_configs.annual_rate for this " +
            "product — a negative rate is accepted by createConfig today.",
    )

    /**
     * Capitalizes one claimed-or-claimable single-currency set (ADR-0033 §B/§D).
     *
     * Rounding happens exactly ONCE, here, and to the **currency's** scale — not scale 4. This is
     * the source: `Money` (and therefore ledger-service, which re-wraps every incoming line as
     * `Money.of(amount, currencyCode)`) rejects an amount whose scale exceeds the currency's minor
     * units, so a scale-4 gross 400s every single money-bearing capitalization at the boundary.
     * Daily accruals stay at scale 6 — `totalAccrued` below is the raw sum — because an accrual is a
     * running measurement, not money. The capitalization is the actual credit, so it is money, and
     * it is rounded here rather than at the port so the capitalization row and the GL carry the
     * identical figures; rounding at the adapter would leave the row and the journal up to 0.005
     * apart.
     *
     * Rounding gross and net independently is safe: [WithholdingTaxPolicy] assesses tax at scale 0
     * (whole CZK, DOWN), so `round(gross) == round(gross − tax) + tax` exactly and the
     * `gross = net + tax` invariant [CapitalizationPosting] enforces survives.
     */
    private fun capitalizeSet(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
        accruals: List<InterestAccrual>,
        alreadyClaimed: Boolean,
    ): Uni<InterestCapitalization> {
        // Normalized so a "czk"/"CZK" mix is one currency, not two.
        val currencies = accruals.map { it.currency.uppercase() }.distinct()
        if (currencies.size > 1) return Uni.createFrom().failure(mixedCurrencyFailure(accountId, productId, currencies))
        val ccy = CurrencyCode.of(currencies.single())
        val total = accruals.fold(BigDecimal.ZERO) { acc, a -> acc + a.accruedAmount }
        val gross = total.setScale(ccy.defaultFractionDigits, RoundingMode.HALF_UP)
        // Before the claim, so a refusal leaves every accrual ACCRUING and nothing to unwind.
        if (gross.signum() < 0) {
            return Uni.createFrom().failure(negativeGrossFailure(accountId, productId, toDate, gross, ccy.code))
        }
        val periodFrom = accruals.minOf { it.accrualDate }
        val now = OffsetDateTime.now(clock)
        // ADR-0033: withhold at the credit (capitalization), crediting net; record the liability.
        // #1355: freeze the tax profile at claim time and replay it on retry (see [claimProfile]),
        // so the withholding row a retry commits matches the journal the ledger idempotently replays.
        return claimProfile(accountId, accruals, alreadyClaimed).flatMap { profile ->
            val tax = WithholdingTaxPolicy.compute(gross, ccy.code, profile, toDate)
            val net = tax.netAmount.setScale(ccy.defaultFractionDigits, RoundingMode.HALF_UP)
            val cap = InterestCapitalization(
                accountId = accountId,
                productId = productId,
                periodFrom = periodFrom,
                periodTo = toDate,
                totalAccrued = total,
                capitalizedAmount = net,
                grossAmount = gross,
                taxAmount = tax.taxAmount,
                netAmount = net,
                currency = ccy.code,
                createdAt = now,
            )
            val withholding = WithholdingTax(
                capitalizationId = cap.id,
                accountId = accountId,
                periodFrom = periodFrom,
                periodTo = toDate,
                taxableBase = tax.taxableBase,
                rate = tax.rate,
                taxAmount = tax.taxAmount,
                currency = ccy.code,
                treatment = tax.treatment,
                exemptCode = tax.exemptCode,
                createdAt = now,
            )
            claim(accruals, toDate, alreadyClaimed, profile)
                // Ledger SECOND, own rows THIRD (ADR-0033 §D) — see [postCreditLeg].
                .flatMap { postCreditLeg(cap, ccy) }
                .flatMap {
                    // ONE transaction for the whole credit: capitalization + withholding + outbox
                    // event + the status-guarded CAPITALIZING -> CAPITALIZED flip. Previously these
                    // were four separate transactions, so a crash between them could commit the
                    // capitalization while leaving the accruals claimable — a retry then re-credited
                    // the customer AND re-booked the tax.
                    capitalizationRepo.saveWithOutbox(
                        cap,
                        withholding,
                        withholdingRecordedEvent(cap, withholding),
                        accruals.map { it.id },
                        now,
                    )
                }
        }
    }

    /**
     * The tax profile to withhold against for this capitalization.
     *
     * On a fresh set it is resolved now and (via [claim]) frozen with the claim. On a retry
     * ([alreadyClaimed]) it is replayed from the snapshot frozen at claim time — NOT re-resolved —
     * so a profile change between the crashed attempt and the retry cannot make the withholding row
     * disagree with the journal the ledger idempotently replays (issue #1355). A claim already in
     * flight before the snapshot columns existed carries none; those fall back to a fresh resolve,
     * which is safe while resolution is constant.
     */
    private fun claimProfile(
        accountId: UUID,
        accruals: List<InterestAccrual>,
        alreadyClaimed: Boolean,
    ): Uni<TaxProfile> = if (alreadyClaimed) {
        accruals.firstNotNullOfOrNull { it.claimedTaxProfile }
            ?.let { Uni.createFrom().item(it) }
            ?: taxProfilePort.resolve(accountId)
    } else {
        taxProfilePort.resolve(accountId)
    }

    /**
     * Freezes the accrual set AND the resolved tax [profile] for [toDate]; a set recovered from a
     * previous attempt is already frozen (both were committed by the interrupted attempt's claim).
     */
    private fun claim(
        accruals: List<InterestAccrual>,
        toDate: LocalDate,
        alreadyClaimed: Boolean,
        profile: TaxProfile,
    ): Uni<Unit> = if (alreadyClaimed) {
        Uni.createFrom().item(Unit)
    } else {
        accrualRepo.claimForCapitalization(accruals.map { it.id }, toDate, profile)
    }

    /**
     * Posts the ADR-0033 §D split to the ledger — DEBIT interest expense (gross), CREDIT the
     * customer's deposit-control pocket (net, sub-ledger = accountId), CREDIT withholding-tax
     * payable (tax) — **after** the accrual set is claimed and **before** the local write set
     * commits.
     *
     * Ordering is deliberate and mirrors `LendingService.accrueOne`'s post-then-mark. A crash
     * between the post and the commit leaves the accruals `CAPITALIZING`, so the retry re-derives
     * the SAME amounts from the SAME claimed set and replays the SAME business-derived idempotency
     * key (`CapitalizationJournalFactory.idempotencyKey` — account + product + period end, never
     * `cap.id`, which is a fresh UUID per attempt); the ledger collapses that onto the journal it
     * already booked. The result is exactly-once on money. The reverse order — commit, then post —
     * would strand a customer credit that exists in interest-service and nowhere in the GL, which
     * no retry can repair because the accruals are already `CAPITALIZED`.
     *
     * The REST call is intentionally OUTSIDE `saveWithOutbox`'s transaction: a network call inside
     * an open DB transaction holds a connection for the ledger's whole round-trip (and its retries),
     * and a ledger timeout would then be indistinguishable from a rolled-back write.
     *
     * A zero-gross period (a zero-balance account still runs the accrual pass) carries no money and
     * no tax: there is nothing to recognize, so no journal is posted — every leg would be zero and
     * the ledger requires ≥2 lines each with `amount > 0`. The capitalization row is still written,
     * which is what V6's partial unique index (`WHERE total_accrued <> 0`) already anticipates as
     * inert bookkeeping. Same branch as lending's zero-interest installment. A NEGATIVE gross is a
     * different matter entirely and never reaches here — [capitalizeSet] refuses it before the claim
     * (see [negativeGrossFailure]).
     */
    private fun postCreditLeg(cap: InterestCapitalization, ccy: CurrencyCode): Uni<Unit> {
        val gross = Money(cap.grossAmount, ccy)
        if (gross.isZero()) return Uni.createFrom().item(Unit)
        return ledgerPostingPort.post(
            CapitalizationPosting(
                accountId = cap.accountId,
                productId = cap.productId,
                periodTo = cap.periodTo,
                gross = gross,
                tax = Money(cap.taxAmount, ccy),
                net = Money(cap.netAmount, ccy),
            ),
        )
    }

    /**
     * Builds the versioned `interest.withholding.recorded` outbox event (ADR-0033 §F).
     *
     * `occurredAt` is [WithholdingTax.createdAt] — the instant this withholding was decided and
     * recorded, stamped from the injected clock in the very transaction this event announces
     * (#8352). Two things about it are worth stating rather than leaving to a reader:
     *
     *  - It was already in hand and simply not projected. The payload's only temporal fields are
     *    `periodFrom`/`periodTo`, which are `LocalDate` ACCRUAL-PERIOD bounds, not an instant —
     *    so `AuditConsumer.eventTime` (which reads `occurredAt` and only `occurredAt`) found no
     *    event time and every audit row for a withholding decision recorded the audit consumer's
     *    ingest clock as the moment tax was withheld from a customer.
     *  - `.toInstant()` is load-bearing, not tidiness: `createdAt` is an `OffsetDateTime`, and
     *    while `Instant.parse` does accept a non-`Z` offset, the ISO-8601 form this service's
     *    `BANK_TIME`-zoned clock would render is not the one the rest of the fleet puts on the
     *    wire. Normalise once here rather than rely on the consumer's tolerance.
     *
     * Additive: every existing field keeps its name, place and form.
     */
    private fun withholdingRecordedEvent(cap: InterestCapitalization, withholding: WithholdingTax): OutboxMessage {
        val payload = "{\"schemaVersion\":1," +
            "\"capitalizationId\":\"${cap.id}\",\"withholdingId\":\"${withholding.id}\"," +
            "\"accountId\":\"${cap.accountId}\",\"productId\":\"${cap.productId}\"," +
            "\"periodFrom\":\"${cap.periodFrom}\",\"periodTo\":\"${cap.periodTo}\"," +
            "\"currency\":\"${cap.currency}\",\"grossAmount\":\"${cap.grossAmount}\"," +
            "\"taxableBase\":\"${withholding.taxableBase}\",\"rate\":\"${withholding.rate}\"," +
            "\"taxAmount\":\"${withholding.taxAmount}\",\"netAmount\":\"${cap.netAmount}\"," +
            "\"treatment\":\"${withholding.treatment}\",\"status\":\"${withholding.status}\"," +
            "\"occurredAt\":\"${withholding.createdAt.toInstant()}\"}"
        return OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = cap.id,
            eventType = "interest.withholding.recorded.v1",
            payload = payload,
        )
    }

    /**
     * Capitalizes every `(account, product)` with a pending `ACCRUING` set up to [toDate] — the
     * fleet-wide monthly run behind the capitalization scheduler (issue #999). Returns the number of
     * pairs actually capitalized.
     *
     * The work-list is discovered from the accrual table itself ([findAccountsWithPendingCapitalization]),
     * NOT by enumerating account-service: capitalization operates purely over already-persisted
     * accruals, so it needs no live balance and does not touch account-service.
     *
     * Per-pair resilience mirrors [accrueAll]: a mixed-currency wedge (#1265), an in-flight claim held
     * for a different period, a non-positive gross, or a lost race (another run already capitalized the
     * pair) collapses that one pair to a no-op (0) and is logged, never aborting the batch — one wedged
     * account must not stop every other account's monthly capitalization. Pairs are processed
     * sequentially (`concatenate`) to keep a bounded, polite load on the ledger.
     */
    override fun capitalizeAll(toDate: LocalDate): Uni<Int> =
        accrualRepo.findAccountsWithPendingCapitalization(toDate).flatMap { pairs ->
            if (pairs.isEmpty()) {
                Uni.createFrom().item(0)
            } else {
                Multi.createFrom().iterable(pairs)
                    .onItem().transformToUniAndConcatenate { (accountId, productId) ->
                        capitalize(accountId, productId, toDate)
                            .map { 1 }
                            .onFailure().invoke { e ->
                                log.warnf(
                                    e,
                                    "capitalization skipped for account %s product %s up to %s: %s",
                                    accountId,
                                    productId,
                                    toDate,
                                    e.message,
                                )
                            }
                            .onFailure().recoverWithItem(0)
                    }
                    .collect().with(java.util.stream.Collectors.summingInt { it })
            }
        }

    override fun listAllAccruals(): Uni<List<InterestAccrual>> = accrualRepo.findAll()

    override fun getAccruals(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>> =
        accrualRepo.findByAccountId(accountId, from, to)

    override fun getSummary(accountId: UUID, from: LocalDate, to: LocalDate): Uni<AccrualSummary> =
        accrualRepo.findByAccountId(accountId, from, to).map { accruals ->
            AccrualSummary(
                accountId = accountId,
                totalAccrued = accruals.fold(BigDecimal.ZERO) { acc, a -> acc + a.accruedAmount },
                currency = accruals.firstOrNull()?.currency ?: "EUR",
                fromDate = from,
                toDate = to,
                accrualCount = accruals.size,
            )
        }

    override fun getCapitalizations(accountId: UUID): Uni<List<InterestCapitalization>> =
        capitalizationRepo.findByAccountId(accountId)

    override fun createConfig(config: InterestRateConfig): Uni<InterestRateConfig> = configRepo.save(config)
    override fun getConfig(id: UUID): Uni<InterestRateConfig?> = configRepo.findById(id)
    override fun listConfigs(productId: String?): Uni<List<InterestRateConfig>> =
        if (productId != null) configRepo.findByProductId(productId) else configRepo.findAll()

    override fun effectiveRate(accountId: UUID, productId: String, date: LocalDate): Uni<InterestRateConfig?> =
        configRepo.findEffectiveRate(accountId, productId, date)

    override fun deactivateConfig(id: UUID): Uni<InterestRateConfig> = configRepo.findById(id).flatMap { config ->
        if (config == null) {
            Uni.createFrom().failure(IllegalArgumentException("Config not found"))
        } else {
            configRepo.update(config.copy(active = false, updatedAt = OffsetDateTime.now(clock)))
        }
    }

    private companion object {
        const val ACCRUAL_PAGE_SIZE = 100
    }
}
