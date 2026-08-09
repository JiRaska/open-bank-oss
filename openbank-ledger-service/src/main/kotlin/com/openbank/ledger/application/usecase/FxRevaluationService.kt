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
 * **The correctness half of #3921 is NOT addressed here.** `idempotencyKey = "fx-reval-{date}"`
 * keys on the business day alone, with no rate identity, and `LedgerService.postJournal` returns
 * the existing entry on a key hit. So a re-run after a corrected or belated fixing lands returns
 * the ORIGINAL entry, emits no new event and reports `posted = true` — a success that changed
 * nothing, leaving the position valued at the superseded rate indefinitely. Fixing that needs a
 * decision about correcting entries (a rate-identity component in the key, or an explicit reversing
 * entry), not a smaller patch; the age gauge above is what makes the condition visible meanwhile.
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

        val entry = ledger.postJournal(
            PostJournalCommand(
                idempotencyKey = "fx-reval-${command.date}",
                transactionId = UUID.nameUUIDFromBytes("fx-reval-${command.date}".toByteArray()),
                entryDate = command.date,
                valueDate = command.date,
                description = "Daily FX revaluation at ČNB fixing ${command.date}${fixingSuffix(fixings)}",
                lines = lines.map { it.toRequest() },
                postedBy = SYSTEM_USER,
                additionalOutboxMessages = { posted -> listOf(fxRevaluedMessage(posted, command.date, movements)) },
            ),
        )

        log.infof("FX revaluation %s posted as %s: %s (fixings %s)", command.date, entry.id, movements, fixings)

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
        val fixing = cnbRates.cnbRate(currency)
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
