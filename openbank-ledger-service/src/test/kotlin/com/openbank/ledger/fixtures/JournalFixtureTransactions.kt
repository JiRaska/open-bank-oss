// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.fixtures

import java.sql.Connection

/**
 * JDBC fixtures that write `journal_entries` / `journal_lines` directly live under the same storage
 * invariants as every other writer (ADR-0372, V44):
 *
 *  - a POSTED journal must net to zero per settlement currency when its transaction COMMITS, so a
 *    fixture that inserts the journal row and its lines statement by statement in autocommit is
 *    refused on the first line — seed inside [journalTransaction];
 *  - a posted journal and its lines are append-only, so a fixture that removes or rewrites them
 *    must say so — pass a [repairReason], which calls `ledger_declare_repair` and leaves a row in
 *    `ledger_repair_audit` exactly as an operator repair would.
 *
 * The connection's autocommit mode is restored afterwards; on failure the transaction is rolled back.
 */
fun <T> Connection.journalTransaction(repairReason: String? = null, block: (Connection) -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        if (repairReason != null) {
            prepareStatement("select ledger_declare_repair(?)").use {
                it.setString(1, repairReason)
                it.execute()
            }
        }
        val result = block(this)
        commit()
        return result
    } catch (failure: Throwable) {
        runCatching { rollback() }
        throw failure
    } finally {
        runCatching { autoCommit = previousAutoCommit }
    }
}
