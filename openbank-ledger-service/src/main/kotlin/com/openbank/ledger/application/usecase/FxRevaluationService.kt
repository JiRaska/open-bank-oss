// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.FxRevaluationResult
import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.application.port.out.CnbRateProvider
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.LedgerEventPublisher
import com.openbank.ledger.domain.event.FxRevaluedEvent
import com.openbank.ledger.domain.model.FxRevaluationInput
import com.openbank.ledger.domain.model.FxRevaluationPosting
import com.openbank.ledger.domain.model.JournalLine
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/**
 * Daily mark-to-ČNB revaluation of foreign FX positions (ADR-0046, refines ADR-0025 #3).
 *
 * Reads the trial balance for each currency's FX position (199x) and its CZK counter-value account
 * (199x-CV), fetches the statutory ČNB fixing from `openbank-fx-service`, and posts a pure-CZK
 * revaluation entry (built by [FxRevaluationPosting]) through [LedgerUseCase.postJournal] with the
 * idempotency key `fx-reval-{date}` — exactly one entry per business day; a same-day re-run is a
 * no-op. The `JournalPosted` event flows through the transactional outbox (ADR-0003); an additional
 * `openbank.ledger.fx.revalued` domain notification is emitted after the post.
 */
@ApplicationScoped
class FxRevaluationService(
    private val ledger: LedgerUseCase,
    private val glAccounts: GlAccountRepository,
    private val cnbRates: CnbRateProvider,
    private val events: LedgerEventPublisher,
) : FxRevaluationUseCase {

    private val log: Logger = Logger.getLogger(FxRevaluationService::class.java)

    override suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult {
        val trialBalance = ledger.getTrialBalance(GetTrialBalanceQuery(command.date))
        val byCode = trialBalance.lines.associateBy { it.code }

        val inputs = mutableListOf<FxRevaluationInput>()
        val movements = mutableMapOf<String, BigDecimal>()

        for ((currency, codes) in CURRENCIES) {
            val rate = cnbRates.cnbRate(currency)
            if (rate == null) {
                log.warnf("No ČNB rate for %s/CZK on %s — skipping its revaluation leg", currency, command.date)
                continue
            }
            val counterValue = glAccounts.findByCode(codes.counterValueCode)
                ?: error("Counter-value GL account ${codes.counterValueCode} missing — is migration V6 applied?")

            // 199x is credited when the bank acquires the currency, so credit − debit is the long position.
            val positionLine = byCode[codes.positionCode]
            val positionForeign = positionLine?.let { it.totalCredit.subtract(it.totalDebit) } ?: BigDecimal.ZERO
            // Counter-value is a normal ASSET: its net (debit − credit) is the CZK already marked.
            val carryCzk = byCode[codes.counterValueCode]?.net ?: BigDecimal.ZERO

            val input = FxRevaluationInput(currency, counterValue.id, positionForeign, rate, carryCzk)
            val movement = FxRevaluationPosting.movement(input)
            if (movement.signum() != 0) {
                inputs += input
                movements[currency] = movement
            }
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
                description = "Daily FX revaluation at ČNB fixing ${command.date}",
                lines = lines.map { it.toRequest() },
                postedBy = SYSTEM_USER,
            ),
        )

        events.publish(REVALUED_TOPIC, entry.id.toString(), FxRevaluedEvent(entry.id, command.date, movements))
        log.infof("FX revaluation %s posted as %s: %s", command.date, entry.id, movements)

        return FxRevaluationResult(command.date, posted = true, journalId = entry.id, movements = movements)
    }

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

    private companion object {
        const val EXCHANGE_DIFF_CODE = "5900"
        const val REVALUED_TOPIC = "openbank.ledger.fx.revalued"
        val SYSTEM_USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000005900")

        // ADR-0046 scope: EUR/USD/GBP against the CZK functional currency.
        val CURRENCIES: Map<String, AccountCodes> = linkedMapOf(
            "EUR" to AccountCodes("1991", "1995"),
            "USD" to AccountCodes("1992", "1996"),
            "GBP" to AccountCodes("1993", "1997"),
        )
    }
}
