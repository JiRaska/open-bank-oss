// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.support

import com.openbank.cardprocessing.application.usecase.ClearingLedgerPoster
import com.openbank.cardprocessing.application.usecase.LedgerPostingRedriver
import com.openbank.cardprocessing.infrastructure.client.TransactionLedgerPostingAdapter
import com.openbank.cardprocessing.infrastructure.scheduler.LedgerPostingRedriveSweep
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal data class PostingLogRecord(val text: String, val thrown: Throwable?)

/** Capture parameters and thrown objects as well as formatted messages: either can carry raw data. */
internal fun capturePostingLogs(block: () -> Unit): List<PostingLogRecord> {
    val records = CopyOnWriteArrayList<PostingLogRecord>()
    val loggers = listOf(
        ClearingLedgerPoster::class.java,
        LedgerPostingRedriver::class.java,
        TransactionLedgerPostingAdapter::class.java,
        LedgerPostingRedriveSweep::class.java,
    ).map { Logger.getLogger(it.name) }
    val levels = loggers.map { it.level }
    val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val parameters = record.parameters.orEmpty()
            val formatted = String.format(Locale.ROOT, record.message, *parameters)
            records += PostingLogRecord(formatted + " " + parameters.joinToString(" "), record.thrown)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }
    loggers.forEach {
        it.addHandler(handler)
        it.level = Level.ALL
    }
    try {
        block()
    } finally {
        loggers.forEachIndexed { index, logger ->
            logger.removeHandler(handler)
            logger.level = levels[index]
        }
    }
    return records.toList()
}
