// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.FxRevaluationResult
import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.application.port.out.CnbRateProvider
import com.openbank.ledger.application.port.out.FxFixingFreshnessPort
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.domain.event.FxRevaluedEvent
import com.openbank.ledger.domain.model.FxRevaluationInput
import com.openbank.ledger.domain.model.FxRevaluationPosting
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Daily mark-to-ČNB revaluation of foreign FX positions (ADR-0046, refines ADR-0025 #3).
 *
 * Reads the trial balance for each currency's FX position (199x) and its CZK counter-value account
 * (199x-CV), fetches the statutory ČNB fixing from `openbank-fx-service`, and posts a pure-CZK
 * revaluation entry (built by [FxRevaluationPosting]) through [LedgerUseCase.postJournal] with the
 * idempotency key `fx-reval-{date}` — exactly one entry per business day; a same-day re-run is a
 * no-op. [FxRevaluedEvent] is enqueued via `PostJournalCommand.additionalOutboxMessages`, so it
 * commits in the SAME transaction as the `JournalPosted`/`AccountBookedChanged` rows the post
 * itself writes (#1201 proposed fix 3) — not a separate post-commit publish that a crash between
 * the two could lose.
 *
 * ### Fixing freshness, and what is still open (#3921)
 *
 * Every attempt reports the resolved fixing's age through [FxFixingFreshnessPort], and the posting
 * records which fixing it used in its description. Both exist because a revaluation cannot fail
 * loudly here: with no rate it returns `posted = false` after one WARN, and with a stale-but-valid
 * rate it returns a perfectly ordinary success. fx-service's validity window is three days and
 * date-blind, so a Friday fixing marking a Monday position is, to every other signal, a healthy run.
 *
 * ### Corrections, and why they are a SUPERSEDING entry rather than a reversal (#3921)
 *
 * The key used to be `fx-reval-{date}` — the business day alone, no rate identity — and
 * `LedgerService.postJournal` returns the existing entry on a key hit. So a re-run after a
 * corrected or belated fixing landed returned the ORIGINAL entry, emitted no event and reported
 * `posted = true`: a success that changed nothing, leaving the position valued at the superseded
 * rate indefinitely. The key now carries [fixingDigest], the identity of the fixings the posting
 * was actually built from, so a different fixing is a different key and can post.
 *
 * That is only safe because **this revaluation is already carry-relative, not absolute.**
 * [FxRevaluationPosting.movement] posts `round(position * rate) − carryCzk`, where `carryCzk` is
 * the counter-value account's trial-balance net, and the trial balance is cumulative to the
 * business day (`entry_date <= :asOf`, `PanacheJournalRepository`). The first posting carries
 * `entryDate = command.date`, so a correcting run READS ITS OWN PREDECESSOR and posts only the
 * difference between the corrected mark and the mark already booked. Two entries, one correct
 * cumulative position — not two marks added together. This is the property that makes the change
 * safe to make at all; without it, giving the key a rate component would double-count the position.
 *
 * The two alternatives were rejected on that basis, not on taste:
 *
 *  - **Reversal-and-repost.** `LedgerUseCase.reverseJournal` exists, so it was available. It
 *    produces three entries where one suffices and buys nothing: the delta the superseding entry
 *    posts is arithmetically identical to (reverse + full repost), because the reversal restores
 *    exactly the `carryCzk` the repost would then mark from. It also has a failure mode the
 *    superseding form does not — a reversal that commits while the repost does not leaves the
 *    position marked at nothing, whereas an interrupted superseding run leaves it marked at the
 *    superseded (merely stale) rate, which is where it already was.
 *  - **Overwriting the original entry.** Not available and correctly so: journal entries are
 *    append-only here, and an attested year refuses new activity outright
 *    (`LedgerService.requireOpenPeriod`). A correction for a closed period must fail loudly, and it
 *    does — as a superseding posting into a locked day, which the day/period locks already govern.
 *
 * The repeat-run case is unchanged and needs no key at all: an identical re-run computes a movement
 * of zero for every leg, contributes no inputs and returns `posted = false` before reaching
 * `postJournal`. The key's remaining job is the narrow one it was always doing — two runs racing
 * before either commits.
 *
 * **Degradation.** When no leg carries a `validFrom` (an fx-service that does not serve it), there
 * is no fixing identity to key on and the key falls back to exactly `fx-reval-{date}`. Corrections
 * are then impossible, as they were before — deliberately, since a fabricated identity would be
 * worse than an honest inability. That state is visible: `openbank_fx_fixing_age_seconds` reads as
 * pod-age and the entry description carries no `[fixings ...]` suffix.
 */
@ApplicationScoped
class FxRevaluationService(
    private val ledger: LedgerUseCase,
    private val glAccounts: GlAccountRepository,
    private val cnbRates: CnbRateProvider,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val fixingFreshness: FxFixingFreshnessPort,
) : FxRevaluationUseCase {

    private val log: Logger = Logger.getLogger(FxRevaluationService::class.java)

    override suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult {
        val trialBalance = ledger.getTrialBalance(GetTrialBalanceQuery(command.date))
        val byCode = trialBalance.lines.associateBy { it.code }

        val inputs = mutableListOf<FxRevaluationInput>()
        val movements = mutableMapOf<String, BigDecimal>()
        val fixings = linkedMapOf<String, Instant>()

        for ((currency, codes) in CURRENCIES) {
            val leg = resolveLeg(currency, codes, byCode, command.date) ?: continue
            inputs += leg.input
            movements[currency] = leg.movement
            leg.validFrom?.let { fixings[currency] = it }
        }

        if (inputs.isEmpty()) {
            log.infof("FX revaluation %s: nothing to revalue (no positions moved)", command.date)
            return FxRevaluationResult(command.date, posted = false, journalId = null, movements = emptyMap())
        }

        val exchangeDiff = glAccounts.findByCode(EXCHANGE_DIFF_CODE)
            ?: error("Exchange-rate-differences GL account $EXCHANGE_DIFF_CODE missing — is migration V5 applied?")

        val journalId = UUID.randomUUID()
        val lines = FxRevaluationPosting.build(journalId, exchangeDiff.id, inputs)

        val idempotencyKey = idempotencyKey(command.date, fixings)
        val entry = ledger.postJournal(
            PostJournalCommand(
                idempotencyKey = idempotencyKey,
                transactionId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()),
                entryDate = command.date,
                valueDate = command.date,
                description = "Daily FX revaluation at ČNB fixing ${command.date}${fixingSuffix(fixings)}",
                lines = lines.map { it.toRequest() },
                postedBy = SYSTEM_USER,
                additionalOutboxMessages = { posted -> listOf(fxRevaluedMessage(posted, command.date, movements)) },
            ),
        )

        log.infof(
            "FX revaluation %s posted as %s under key %s: %s (fixings %s)",
            command.date,
            entry.id,
            idempotencyKey,
            movements,
            fixings,
        )

        return FxRevaluationResult(command.date, posted = true, journalId = entry.id, movements = movements)
    }

    /**
     * One currency's revaluation leg, or `null` when there is nothing to post for it — either no
     * ČNB fixing was available, or the position has not moved since the last mark.
     *
     * The freshness report happens here, unconditionally, **before** either null return. Reporting
     * only the resolved legs would publish an age exclusively for the currencies that are working,
     * which is the inverse of what the signal is for.
     */
    private suspend fun resolveLeg(
        currency: String,
        codes: AccountCodes,
        byCode: Map<String, TrialBalanceLine>,
        date: LocalDate,
    ): RevaluationLeg? {
        // The BUSINESS DAY being marked, not "now": a belated or manual run for an older date used
        // to be marked at today's fixing, because the port had no date to ask with (#3921 item 3).
        val fixing = cnbRates.cnbRate(currency, date)
        // Report EVERY attempt, resolved or not — a null here deliberately leaves the currency's
        // published age climbing instead of blanking the series (#3921).
        fixingFreshness.fixingObserved(currency, fixing?.validFrom)
        if (fixing == null) {
            log.warnf("No ČNB rate for %s/CZK on %s — skipping its revaluation leg", currency, date)
            return null
        }
        val counterValue = glAccounts.findByCode(codes.counterValueCode)
            ?: error("Counter-value GL account ${codes.counterValueCode} missing — is migration V6 applied?")

        // 199x is credited when the bank acquires the currency, so credit − debit is the long position.
        val positionLine = byCode[codes.positionCode]
        val positionForeign = positionLine?.let { it.totalCredit.subtract(it.totalDebit) } ?: BigDecimal.ZERO
        // Counter-value is a normal ASSET: its net (debit − credit) is the CZK already marked.
        val carryCzk = byCode[codes.counterValueCode]?.net ?: BigDecimal.ZERO

        val input = FxRevaluationInput(currency, counterValue.id, positionForeign, fixing.rate, carryCzk)
        val movement = FxRevaluationPosting.movement(input)
        return if (movement.signum() == 0) null else RevaluationLeg(input, movement, fixing.validFrom)
    }

    /**
     * Renders the fixings the posting was actually built from, e.g. ` [fixings EUR@2026-08-07T13:15:00Z]`.
     *
     * The entry used to record only the business day it marked, never the rate it marked at, so
     * "which fixing valued this position" was unanswerable from the ledger — and it is the question
     * that matters when a corrected fixing arrives after the run (#3921). Empty when no leg carried
     * a fixing date, so an fx-service that does not send `validFrom` leaves the description exactly
     * as it was rather than adding an empty bracket.
     */
    /**
     * The posting's idempotency key: the business day, plus the identity of the fixings it was
     * built from once any leg carries one.
     *
     * A digest rather than the instants themselves for two reasons: the key is bounded regardless
     * of how many currencies ADR-0046's scope grows to, and it is a key, not a record. The record
     * is the entry's own description, which spells every fixing out in full — so the opaque
     * component is always resolvable from the entry it keys, which is the only place anyone
     * reading it would look.
     *
     * Order-independent by construction (sorted before hashing), so the same set of fixings always
     * yields the same key no matter what order the currency loop resolved them in.
     */
    private fun idempotencyKey(date: LocalDate, fixings: Map<String, Instant>): String =
        if (fixings.isEmpty()) "fx-reval-$date" else "fx-reval-$date-${fixingDigest(fixings)}"

    private fun fixingDigest(fixings: Map<String, Instant>): String {
        val canonical = fixings.entries.sortedBy { it.key }.joinToString(",") { "${it.key}@${it.value}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(DIGEST_CHARS)
    }

    private fun fixingSuffix(fixings: Map<String, Instant>): String =
        if (fixings.isEmpty()) "" else fixings.entries.joinToString(", ", " [fixings ", "]") { "${it.key}@${it.value}" }

    private fun fxRevaluedMessage(entry: JournalEntry, date: LocalDate, movements: Map<String, BigDecimal>) =
        OutboxMessage(
            aggregateId = entry.id,
            eventType = FX_REVALUED,
            payload = objectMapper.writeValueAsString(
                FxRevaluedEvent(
                    aggregateId = entry.id,
                    date = date,
                    movements = movements,
                    occurredAt = Instant.now(clock),
                ),
            ),
            createdAt = Instant.now(clock),
        )

    private fun JournalLine.toRequest() = JournalLineRequest(
        glAccountId = glAccountId,
        side = side,
        amount = amount.amount,
        currencyCode = amount.currency.code,
        fxRate = fxRate,
        baseAmount = baseAmount.amount,
        baseCurrencyCode = baseAmount.currency.code,
    )

    private data class AccountCodes(val positionCode: String, val counterValueCode: String)

    /** A leg that will contribute to the posting, with the fixing instant it was valued at. */
    private data class RevaluationLeg(val input: FxRevaluationInput, val movement: BigDecimal, val validFrom: Instant?)

    private companion object {
        const val EXCHANGE_DIFF_CODE = "5900"

        // 48 bits of the SHA-256 over the fixing set. The collision this must avoid is not
        // adversarial — it is two DIFFERENT fixing sets for one business day hashing alike, which
        // would silently restore the old "correction is a no-op" behaviour. The candidate set for a
        // single day is a handful of values, so 2^48 is many orders of magnitude of headroom, and a
        // longer key buys nothing legible.
        const val DIGEST_CHARS = 12
        const val FX_REVALUED = "FxRevalued"
        val SYSTEM_USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000005900")

        // ADR-0046 scope: EUR/USD/GBP against the CZK functional currency.
        val CURRENCIES: Map<String, AccountCodes> = linkedMapOf(
            "EUR" to AccountCodes("1991", "1995"),
            "USD" to AccountCodes("1992", "1996"),
            "GBP" to AccountCodes("1993", "1997"),
        )
    }
}
