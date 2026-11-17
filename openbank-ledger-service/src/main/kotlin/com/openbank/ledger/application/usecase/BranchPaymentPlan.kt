// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class BranchPaymentReceipt(val transactionId: UUID, val bankId: UUID, val journalIds: List<UUID>)

data class BranchPaymentRequest(
    val idempotencyKey: String,
    val transactionId: UUID,
    val bankId: UUID,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val sourceBookId: UUID?,
    val targetBookId: UUID?,
    val amount: BigDecimal,
    val currency: String,
    val entryDate: LocalDate,
    val valueDate: LocalDate,
    val createdBy: UUID,
) {
    fun validate() {
        require(idempotencyKey == "payment:$transactionId") { "Branch payment reference does not match transaction" }
        require(sourceAccountId == null || sourceAccountId != targetAccountId) {
            "Branch payment requires distinct accounts"
        }
        require(sourceAccountId != null || targetAccountId != null) { "A customer account is required" }
        require((sourceAccountId == null) == (sourceBookId == null)) { "Source book attribution is required" }
        require((targetAccountId == null) == (targetBookId == null)) { "Target book attribution is required" }
        require(amount.signum() > 0) { "Amount must be positive" }
        com.openbank.libs.domain.money.Money.of(amount, currency)
    }

    fun digest(synthetic: Boolean): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(
            transactionId, bankId, sourceAccountId, targetAccountId, sourceBookId, targetBookId,
            amount.stripTrailingZeros().toPlainString(), currency, entryDate, valueDate, createdBy, synthetic,
        )
            .joinToString("|").toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it) }

    fun commands(synthetic: Boolean): List<PostJournalCommand> {
        val deposit = account("210")
        val source = sourceAccountId
        val target = targetAccountId
        val crossBook = source != null && target != null && sourceBookId != targetBookId
        val clearing = if (crossBook) account("129") else account("110")
        fun line(gl: UUID, side: JournalSide, sub: UUID? = null) =
            JournalLineRequest(gl, side, amount, currency, null, amount, currency, sub)
        fun journal(suffix: String, book: UUID?, lines: List<JournalLineRequest>) = PostJournalCommand(
            "$idempotencyKey:$suffix", transactionId, entryDate, valueDate, "Branch payment", lines, createdBy,
            synthetic = synthetic, branchBookId = book,
        )
        return when {
            crossBook -> listOf(
                journal(
                    "source",
                    sourceBookId,
                    listOf(line(deposit, JournalSide.DEBIT, source), line(clearing, JournalSide.CREDIT)),
                ),
                journal(
                    "target",
                    targetBookId,
                    listOf(line(clearing, JournalSide.DEBIT), line(deposit, JournalSide.CREDIT, target)),
                ),
            )
            source != null && target != null -> listOf(
                journal(
                    "same-book",
                    sourceBookId,
                    listOf(line(deposit, JournalSide.DEBIT, source), line(deposit, JournalSide.CREDIT, target)),
                ),
            )
            source != null -> listOf(
                journal(
                    "source",
                    sourceBookId,
                    listOf(line(deposit, JournalSide.DEBIT, source), line(clearing, JournalSide.CREDIT)),
                ),
            )
            else -> listOf(
                journal(
                    "target",
                    targetBookId,
                    listOf(line(clearing, JournalSide.DEBIT), line(deposit, JournalSide.CREDIT, target)),
                ),
            )
        }
    }

    private fun account(prefix: String): UUID {
        val index = listOf("CZK", "EUR", "USD", "GBP").indexOf(currency)
        require(index >= 0) { "No branch payment accounts for currency" }
        val suffix = when (prefix) {
            "210" -> if (index == 0) "000002" else "00210$index"
            "110" -> if (index == 0) "000001" else "00110$index"
            else -> "00129$index"
        }
        return UUID.fromString("a0000000-0000-0000-0000-000000$suffix")
    }
}
