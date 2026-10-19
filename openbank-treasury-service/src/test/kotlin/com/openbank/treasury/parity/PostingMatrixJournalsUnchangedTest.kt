// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.openbank.treasury.domain.LedgerMapGolden
import com.openbank.treasury.domain.glCode
import com.openbank.treasury.domain.model.AccountingClasses
import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.PostingMatrix
import com.openbank.treasury.domain.reference.Ifrs9Category
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * ADR-0341 D3 x ADR-0344 D7: the posting-rule matrix names accounting class + role, and the ledger
 * map (V33, mirrored by [LedgerMapGolden]) resolves them. The journals of every matrix fixture's whole
 * life must land on EXACTLY the GL accounts, sides, amounts, order and keys the matrix produced when
 * it still named GL codes. The golden was rendered once from that code and is never regenerated from
 * the code under test: every non-accrual journal verbatim, every accrual's account shape, and a hash
 * over all of them.
 */
class PostingMatrixJournalsUnchangedTest {
    private val golden = javaClass.getResource("/posting-matrix-journals-golden.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    private val now = MatrixJournalRender.render { it.glCode() }

    @Test
    fun `every non-accrual journal is unchanged, line for line`() {
        val expected = golden.filter { it.startsWith("J ") }.map { it.removePrefix("J ") }
        assertThat(now.filter { ":accrual:" !in it }).containsExactlyElementsOf(expected)
        assertThat(expected).hasSizeGreaterThan(100)
    }

    @Test
    fun `every daily accrual posts to the same accounts`() {
        val expected = golden.filter { it.startsWith("A ") }.map { it.removePrefix("A ") }.toSet()
        val shapes = now.filter { ":accrual:" in it }.map { l ->
            val (head, body) = l.split(" :: ")
            head.substringBefore(" treasury:") + " accrual :: " +
                body.split(" | ").joinToString(" | ") { it.replace(Regex(" [0-9.]+ "), " ") }
        }.toSet()
        assertThat(shapes).isEqualTo(expected)
    }

    @Test
    fun `the whole render, accrual amounts included, hashes to the golden`() {
        val (_, hash, count) = golden.first { it.startsWith("sha256-all ") }.split(" ")
        val text = now.joinToString("\n") + "\n"
        val actual = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        assertThat(now).hasSize(count.toInt())
        assertThat(actual).isEqualTo(hash)
    }

    @Test
    fun `every class, role and currency a matrix line can produce is mapped by the ledger`() {
        val unmapped = PostingMatrix.rules.flatMap { r -> r.lines.map { r to it } }.flatMap { (r, t) ->
            val categories = r.ifrs9Category?.let { listOf(it) } ?: Ifrs9Category.entries
            categories.flatMap { cat ->
                listOf("CZK", "EUR").map { ccy ->
                    Triple(PostingMatrix.accountingClass(t.accountingClass, cat), t.role.name, ccy)
                }
            }
        }.toSet().filter { it !in LedgerMapGolden.treasury.keys }
        // CZK-only legacy classes (ČNB facilities) have no EUR row by design.
        assertThat(unmapped.filter { it.first !in setOf("CNB_DEPOSIT_FACILITY", "CNB_LOMBARD_BORROWING") }).isEmpty()
        assertThat(LedgerRole.entries.map { it.name }).contains("MEMO_CONTRA")
        assertThat(AccountingClasses.SECURITY_FVOCI).isEqualTo("DEBT_SECURITY_FVOCI")
    }
}
